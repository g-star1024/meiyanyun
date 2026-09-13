package com.meiyun.ai.chatbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.ai.audit.AuditRecorder;
import com.meiyun.ai.domain.AiChatMessage;
import com.meiyun.ai.domain.AiChatMessageRepository;
import com.meiyun.ai.domain.AiChatSession;
import com.meiyun.ai.domain.AiChatSessionRepository;
import com.meiyun.ai.domain.AiInvokeLogRepository;
import com.meiyun.ai.feature.FeatureInvokeService;
import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import com.meiyun.security.SecurityContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 客服工作台（A1-07）：会话/消息全部来自真实操作并沉淀 ai_chat_session / ai_chat_message；
 * AI 回复走 chatbot 功能 invoke 全治理链（角色灰度矩阵 / 门店灰度 / 敏感词 / 配额 / 计费 /
 * ai_invoke_log），顾客消息先落库再出站，模型失败时顾客消息不丢、接口如实返回 502 引导重试。
 *
 * <p>诚实口径：不 seed 任何历史会话与消息（空库即工作台诚实空态）；不编造知识库命中
 * （结构化知识检索为远期能力，前端知识命中卡仅在 AI 真实回复后展示合规说明，不伪造命中文档）；
 * 转人工仅为站内状态登记（M4-09 咨询工作台真实联动为远期 Backlog）；KPI 全部来自当日真实计数，
 * 无昨日基数时环比不可算（null）。
 */
@Service
public class ChatbotService {

    public static final String FEATURE_CODE = "chatbot";
    public static final String MODEL_VERSION = "v1-2026-09";

    private static final ZoneOffset BJ = ZoneOffset.ofHours(8);
    private static final int CONTENT_MAX = 2000;
    private static final int LAST_MAX = 500;
    private static final int NAME_MAX = 64;
    private static final int CONTEXT_TURNS = 6;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter HM_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final AiChatSessionRepository sessionRepo;
    private final AiChatMessageRepository messageRepo;
    private final AiInvokeLogRepository invokeLogRepo;
    private final FeatureInvokeService featureInvokeService;
    private final AuditRecorder audit;
    private final ObjectMapper json = new ObjectMapper();

    public ChatbotService(AiChatSessionRepository sessionRepo,
                          AiChatMessageRepository messageRepo,
                          AiInvokeLogRepository invokeLogRepo,
                          FeatureInvokeService featureInvokeService,
                          AuditRecorder audit) {
        this.sessionRepo = sessionRepo;
        this.messageRepo = messageRepo;
        this.invokeLogRepo = invokeLogRepo;
        this.featureInvokeService = featureInvokeService;
        this.audit = audit;
    }

    // ============================ DTO ============================

    public record CreateSessionCmd(String customerName, String firstMessage) {
    }

    public record MessageCmd(String content) {
    }

    public record SessionView(Long sessionId, String sessionNo, String customerName, String channel,
                              String lastMessage, String lastSender, int unreadCount,
                              boolean transferred, String time) {
    }

    public record MessageView(Long messageId, String from, String content, String time,
                              Long invokeLogId, String modelCode, Integer totalTokens,
                              Long costFen, Long latencyMs) {
    }

    public record SessionDetail(SessionView session, List<MessageView> messages) {
    }

    public record ChatbotStats(long sessionCount, long aiResolvedCount, long transferredCount,
                               long messageCount, long aiReplyCount,
                               Integer aiResolveRate, Integer transferRate,
                               Long avgLatencyMs,
                               Integer sessionDeltaPct, Integer messageDeltaPct,
                               long weekInvokes, String modelVersion, String modelNote) {
    }

    public record ActionResult(boolean changed, Long sessionId, String action) {
    }

    // ============================ 业务方法 ============================

    /** 会话列表（按渠道过滤）：数据全部来自真实会话表，最近创建在前；空库返回空列表走诚实空态。 */
    @Transactional(readOnly = true)
    public List<SessionView> sessions(String channel) {
        requireUser();
        List<AiChatSession> rows = sessionRepo.findTop100ByOrderBySessionIdDesc();
        List<SessionView> out = new ArrayList<>();
        for (AiChatSession s : rows) {
            if (channel != null && !channel.isBlank() && !"all".equalsIgnoreCase(channel.trim())
                    && !channel.trim().equalsIgnoreCase(s.getChannel())) {
                continue;
            }
            out.add(toSessionView(s));
        }
        return out;
    }

    /**
     * 新建会话：落顾客称呼，可带首条顾客消息（带则同步生成 AI 回复），编号 CS+日期+当日序列。
     * 刻意不加方法级事务：会话与顾客消息各自即时落库，随后 invoke 若失败（502）不回滚顾客消息，
     * 与 FeatureInvokeService 出站日志独立提交保持同一语义（CREATE 审计先于出站记录）。
     */
    public SessionDetail createSession(CreateSessionCmd cmd) {
        LoginUser user = requireUser();
        String name = normalizeName(cmd == null ? null : cmd.customerName());
        AiChatSession s = new AiChatSession();
        s.setSessionNo(nextSessionNo());
        s.setCustomerName(name);
        s.setChannel("ai");
        s.setLastSender("customer");
        s.setUnreadCount(0);
        s.setStaffId(user.staffId());
        s.setStaffName(user.staffName());
        s.setStoreCode(user.storeCode() == null ? "" : user.storeCode());
        AiChatSession saved = sessionRepo.save(s);

        String first = cmd == null || cmd.firstMessage() == null ? "" : cmd.firstMessage().trim();
        audit.record("AI_CHATBOT", "SESS-" + saved.getSessionId(),
                DataScope.currentActor(), "CREATE_CHAT_SESSION",
                payload(Map.of("sessionId", saved.getSessionId(), "sessionNo", saved.getSessionNo(),
                        "customerName", name, "withFirstMessage", !first.isEmpty())));
        if (!first.isEmpty()) {
            appendCustomerAndReply(saved, first, user);
        }
        return toDetail(requireSession(saved.getSessionId()), user);
    }

    /** 会话详情：消息时间线（append-only），打开即清未读。 */
    @Transactional
    public SessionDetail openSession(Long sessionId) {
        LoginUser user = requireUser();
        AiChatSession s = requireSession(sessionId);
        if (s.getUnreadCount() != null && s.getUnreadCount() > 0) {
            s.setUnreadCount(0);
            sessionRepo.save(s);
        }
        return toDetail(s, user);
    }

    /**
     * 顾客发问并由当前渠道接待：先落顾客消息（会话头冗余同步、未读 +1，即时提交）；
     * AI 接待会话走 chatbot invoke 全治理链落 AI 回复（带最近 3 轮上下文）；
     * 人工接待会话仅落顾客消息并提示由座席在输入框人工回复。
     * 刻意不加方法级事务：模型失败抛 502 时顾客消息仍已落库不丢失，前端可原样重试，
     * 与 FeatureInvokeService 成功/失败均独立提交 ai_invoke_log 的语义一致。
     */
    public SessionDetail customerMessage(Long sessionId, MessageCmd cmd) {
        LoginUser user = requireUser();
        AiChatSession s = requireSession(sessionId);
        String content = normalizeContent(cmd == null ? null : cmd.content());
        appendCustomerMessage(s, content, user);
        if ("ai".equalsIgnoreCase(s.getChannel()) && !Boolean.TRUE.equals(s.getTransferred())) {
            invokeAiReply(s, user);
        }
        return toDetail(requireSession(sessionId), user);
    }

    /** 转人工：仅 AI 接待会话可翻转（幂等），站内登记，M4-09 工作台真实联动为远期 Backlog。 */
    @Transactional
    public ActionResult transfer(Long sessionId) {
        LoginUser user = requireUser();
        AiChatSession s = requireSession(sessionId);
        if (Boolean.TRUE.equals(s.getTransferred()) || "human".equalsIgnoreCase(s.getChannel())) {
            return new ActionResult(false, sessionId, "transfer");
        }
        s.setTransferred(true);
        s.setChannel("human");
        s.setTransferredAt(OffsetDateTime.now());
        s.setTransferredBy(user.staffId());
        sessionRepo.save(s);
        audit.record("AI_CHATBOT", "SESS-" + sessionId,
                DataScope.currentActor(), "TRANSFER_HUMAN",
                payload(Map.of("sessionId", sessionId, "sessionNo", s.getSessionNo(),
                        "staffId", user.staffId())));
        return new ActionResult(true, sessionId, "transfer");
    }

    /**
     * 人工座席回复：人工渠道（已转人工）可用，落 staff 消息并清未读；AI 接待会话请先转人工。
     * 刻意不加方法级事务：消息与会话头为纯本地两写，末尾 toDetail 需在新事务中回读
     * PG 默认生成的 created_at（同事务内查询命中一级缓存的未回填实体会致时间为空串）。
     */
    public SessionDetail staffReply(Long sessionId, MessageCmd cmd) {
        LoginUser user = requireUser();
        AiChatSession s = requireSession(sessionId);
        if (!"human".equalsIgnoreCase(s.getChannel()) && !Boolean.TRUE.equals(s.getTransferred())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "当前为 AI 接待会话，请先点击「转人工」后再发送人工回复");
        }
        String content = normalizeContent(cmd == null ? null : cmd.content());
        AiChatMessage m = new AiChatMessage();
        m.setSessionId(sessionId);
        m.setSender("staff");
        m.setContent(content);
        m.setStaffId(user.staffId());
        m.setStaffName(user.staffName());
        messageRepo.save(m);
        s.setLastMessage(tail(content));
        s.setLastSender("staff");
        s.setUnreadCount(0);
        sessionRepo.save(s);
        return toDetail(requireSession(sessionId), user);
    }

    /** 页头 KPI：今日会话/AI 独立解决/转人工/平均响应（真实计数 + 与昨日真实环比）。 */
    @Transactional(readOnly = true)
    public ChatbotStats stats() {
        requireUser();
        OffsetDateTime todayStart = LocalDate.now(BJ).atStartOfDay().atOffset(BJ);
        OffsetDateTime yStart = todayStart.minusDays(1);
        OffsetDateTime weekStart = OffsetDateTime.now(BJ)
                .with(DayOfWeek.MONDAY).toLocalDate().atStartOfDay().atOffset(BJ);

        long sessionsToday = sessionRepo.countByCreatedAtGreaterThanEqual(todayStart);
        long transferredToday = sessionRepo.countByTransferredTrueAndCreatedAtGreaterThanEqual(todayStart);
        long aiResolved = Math.max(0L, sessionsToday - transferredToday);
        long messagesToday = messageRepo.countByCreatedAtGreaterThanEqual(todayStart);
        long aiRepliesToday = messageRepo.countBySenderAndCreatedAtGreaterThanEqual("ai", todayStart);

        Double avgLatencyAvg = messageRepo.avgAiLatencySince(todayStart);
        Long avgLatency = avgLatencyAvg == null ? null : Math.round(avgLatencyAvg);

        long sessionsYesterday = sessionRepo.countByCreatedAtBetween(yStart, todayStart);
        long messagesYesterday = messageRepo.countByCreatedAtBetween(yStart, todayStart);
        long weekInvokes = invokeLogRepo.countByInvokedAtGreaterThanEqualAndFeatureCode(weekStart, FEATURE_CODE);

        return new ChatbotStats(
                sessionsToday, aiResolved, transferredToday, messagesToday, aiRepliesToday,
                rate(aiResolved, sessionsToday), rate(transferredToday, sessionsToday),
                avgLatency, pctChange(sessionsToday, sessionsYesterday),
                pctChange(messagesToday, messagesYesterday),
                weekInvokes, MODEL_VERSION,
                "AI 回复由 chatbot 功能真实出站生成，经敏感词过滤与功能灰度链治理；"
                        + "AI 独立解决=今日未转人工会话；转人工为站内登记，M4-09 工作台真实联动与知识库检索命中为远期能力");
    }

    // ============================ 内部组装 ============================

    private void appendCustomerAndReply(AiChatSession s, String content, LoginUser user) {
        appendCustomerMessage(s, content, user);
        invokeAiReply(s, user);
    }

    private void appendCustomerMessage(AiChatSession s, String content, LoginUser user) {
        AiChatMessage m = new AiChatMessage();
        m.setSessionId(s.getSessionId());
        m.setSender("customer");
        m.setContent(content);
        messageRepo.save(m);
        s.setLastMessage(tail(content));
        s.setLastSender("customer");
        s.setUnreadCount((s.getUnreadCount() == null ? 0 : s.getUnreadCount()) + 1);
        sessionRepo.save(s);
    }

    /** AI 回复：最近 3 轮上下文 + 医美客服合规系统提示组装 input → invoke 全治理链 → 落 ai 消息并清未读。 */
    private void invokeAiReply(AiChatSession s, LoginUser user) {
        String question = s.getLastMessage();
        String input = composeInput(s, question);
        FeatureInvokeService.InvokeView v = featureInvokeService.invoke(
                FEATURE_CODE,
                new FeatureInvokeService.InvokeCmd(input, s.getStoreCode() == null ? "" : s.getStoreCode()));

        AiChatMessage m = new AiChatMessage();
        m.setSessionId(s.getSessionId());
        m.setSender("ai");
        m.setContent(truncate(v.content() == null ? "" : v.content(), CONTENT_MAX));
        m.setInvokeLogId(v.logId());
        m.setModelCode(v.modelCode());
        m.setTotalTokens(v.totalTokens());
        m.setCostFen(v.costFen() == null ? 0L : v.costFen());
        m.setLatencyMs(v.latencyMs());
        m.setStaffId(user.staffId());
        m.setStaffName(user.staffName());
        messageRepo.save(m);

        s.setLastMessage(tail(m.getContent()));
        s.setLastSender("ai");
        s.setUnreadCount(0);
        sessionRepo.save(s);
    }

    private String composeInput(AiChatSession s, String question) {
        StringBuilder sb = new StringBuilder();
        sb.append("【角色】你是医美连锁门店的 AI 客服，仅基于通用医美常识礼貌作答；")
                .append("不得做疾病诊断、不得承诺疗效或使用「零恢复期/绝对安全/永久」等绝对化表述，")
                .append("涉及禁忌、用药、过敏、术后异常时建议联系门店或遵医嘱；回复 150 字以内、口语化。\n");
        List<AiChatMessage> history = messageRepo.findBySessionIdOrderByMessageIdAsc(s.getSessionId());
        int start = Math.max(0, history.size() - CONTEXT_TURNS);
        for (int i = start; i < history.size(); i++) {
            AiChatMessage h = history.get(i);
            String role = switch (h.getSender()) {
                case "ai" -> "AI 客服";
                case "staff" -> "人工客服";
                default -> "顾客";
            };
            sb.append(role).append("：").append(truncate(h.getContent(), 300)).append('\n');
        }
        String customerName = s.getCustomerName();
        sb.append("顾客称呼：")
                .append(customerName == null || customerName.isBlank() ? "顾客" : customerName).append('\n');
        sb.append("请回复顾客最新问题：").append(question);
        return truncate(sb.toString(), 4000);
    }

    private SessionDetail toDetail(AiChatSession s, LoginUser user) {
        return new SessionDetail(toSessionView(s),
                messageRepo.findBySessionIdOrderByMessageIdAsc(s.getSessionId()).stream()
                        .map(this::toMessageView).toList());
    }

    private SessionView toSessionView(AiChatSession s) {
        return new SessionView(s.getSessionId(), s.getSessionNo(), s.getCustomerName(),
                s.getChannel(), s.getLastMessage(), s.getLastSender(),
                s.getUnreadCount() == null ? 0 : s.getUnreadCount(),
                Boolean.TRUE.equals(s.getTransferred()), bjHM(s.getUpdatedAt()));
    }

    private MessageView toMessageView(AiChatMessage m) {
        return new MessageView(m.getMessageId(), m.getSender(), m.getContent(),
                bjHM(m.getCreatedAt()), m.getInvokeLogId(), m.getModelCode(),
                m.getTotalTokens(), m.getCostFen(), m.getLatencyMs());
    }

    private AiChatSession requireSession(Long id) {
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话 id 不能为空");
        }
        return sessionRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "客服会话不存在（id=" + id + "）"));
    }

    /** 会话编号：CS+yyyyMMdd+当日已有会话数+1（4 位）。 */
    private String nextSessionNo() {
        String day = LocalDate.now(BJ).format(DATE_FMT);
        OffsetDateTime dayStart = LocalDate.now(BJ).atStartOfDay().atOffset(BJ);
        return "CS" + day + String.format("%04d", sessionRepo.countByCreatedAtGreaterThanEqual(dayStart) + 1);
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "顾客称呼不能为空");
        }
        String n = name.trim();
        if (n.length() > NAME_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "顾客称呼过长，不超过 " + NAME_MAX + " 字");
        }
        return n;
    }

    private String normalizeContent(String content) {
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息内容不能为空");
        }
        String c = content.trim();
        if (c.length() > CONTENT_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "消息内容过长，单条不超过 " + CONTENT_MAX + " 字");
        }
        return c;
    }

    private Integer rate(long part, long total) {
        if (total <= 0) {
            return null;
        }
        return (int) Math.round(part * 100.0 / total);
    }

    /** 环比百分比：前期为 0 且本期 >0 → null（不可算），双 0 → 0。 */
    private Integer pctChange(long current, long previous) {
        if (previous == 0) {
            return current == 0 ? 0 : null;
        }
        return (int) Math.round((current - previous) * 100.0 / previous);
    }

    private String tail(String content) {
        return truncate(content, LAST_MAX);
    }

    private String bjHM(OffsetDateTime t) {
        return t == null ? "" : t.withOffsetSameInstant(BJ).toLocalDateTime().format(HM_FMT);
    }

    private LoginUser requireUser() {
        LoginUser user = SecurityContext.get();
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已失效，请重新登录");
        }
        return user;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    private String payload(Map<String, ?> data) {
        try {
            return json.writeValueAsString(data);
        } catch (Exception e) {
            return "{}";
        }
    }
}
