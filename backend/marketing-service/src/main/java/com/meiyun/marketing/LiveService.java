package com.meiyun.marketing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.marketing.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 直播团购写链路（M5-05）：场次创建 / 开播 / 结束；短视频库本期只读。
 *
 * <p>写接口四件套：① 参数校验（平台白名单、标题/开播时间必填、简介文案合规）；
 * ② 幂等（开播仅 NOT_STARTED 可执行、结束仅 LIVE 可执行，状态不符返回 false 不审计）；
 * ③ 全动作审计（bizType=LIVE_SESSION，CREATE/START/END，payload JSON）；④ 中文错误。
 * 金额口径：dealAmount bigint 存「分」。
 */
@Service
public class LiveService {

    public static final List<String> PLATFORMS = List.of("DOUYIN", "WECHAT_CHANNEL");

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final LiveSessionRepository sessionRepo;
    private final ShortVideoRepository videoRepo;
    private final BizNoGenerator noGen;
    private final AuditRecorder audit;
    private final ForbiddenWordService forbiddenWordService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LiveService(LiveSessionRepository sessionRepo, ShortVideoRepository videoRepo,
                       BizNoGenerator noGen, AuditRecorder audit,
                       ForbiddenWordService forbiddenWordService) {
        this.sessionRepo = sessionRepo;
        this.videoRepo = videoRepo;
        this.noGen = noGen;
        this.audit = audit;
        this.forbiddenWordService = forbiddenWordService;
    }

    // ==================== 查询 ====================

    public List<LiveSession> listSessions() {
        return sessionRepo.findAllByOrderByStartTimeDesc();
    }

    public List<ShortVideo> listVideos() {
        return videoRepo.findAllByOrderByPublishedAtDesc();
    }

    // ==================== 写动作 ====================

    /** 创建场次：状态 NOT_STARTED，漏斗/成交初始为 0，主播取当前登录人。 */
    @Transactional
    public LiveSession createSession(SessionCmd cmd) {
        String title = cmd.title() == null ? "" : cmd.title().trim();
        String platform = cmd.platform() == null ? "" : cmd.platform().trim();
        String intro = cmd.intro() == null ? "" : cmd.intro().trim();
        if (title.isEmpty() || title.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "直播标题不可为空且长度不超过 64 字");
        }
        if (!PLATFORMS.contains(platform)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "直播平台不合法（DOUYIN/WECHAT_CHANNEL）");
        }
        LocalDateTime startTime = parseTime(cmd.startTime());
        if (intro.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "直播简介长度不超过 500 字");
        }
        if (!intro.isEmpty()) {
            List<String> hits = forbiddenWordService.check(title + "\n" + intro);
            if (!hits.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "营销合规拦截：命中违禁词 " + String.join("、", hits));
            }
        }
        List<String> couponIds = normalizeCodes(cmd.mountedCouponIds());

        LiveSession s = new LiveSession();
        s.setSessionId(noGen.next("LS", like -> sessionRepo
                .findTopBySessionIdLikeOrderBySessionIdDesc(like).map(LiveSession::getSessionId).orElse(null)));
        s.setTitle(title);
        s.setPlatform(platform);
        s.setStatus("NOT_STARTED");
        s.setStartTime(startTime);
        s.setViewers(0);
        s.setLinkClicks(0);
        s.setDealCount(0);
        s.setDealAmount(0L);
        s.setMountedCouponIds(writeJson(couponIds));
        s.setIntro(intro.isEmpty() ? null : intro);
        String actor = DataScope.currentActor();
        s.setHost("system".equals(actor) ? "运营" : actor);
        s.setCreatedAt(OffsetDateTime.now());
        LiveSession saved = sessionRepo.save(s);
        audit("CREATE", saved.getSessionId(), Map.of(
                "title", title, "platform", platform,
                "startTime", startTime.format(DT_FMT), "mountedCouponIds", couponIds));
        return saved;
    }

    /** 开播：NOT_STARTED → LIVE（状态不符返回 false 不审计）。 */
    @Transactional
    public boolean startLive(String sessionId) {
        LiveSession s = mustGet(sessionId);
        if (!"NOT_STARTED".equals(s.getStatus())) {
            return false;
        }
        s.setStatus("LIVE");
        sessionRepo.save(s);
        audit("START", sessionId, Map.of("title", s.getTitle(), "platform", s.getPlatform()));
        return true;
    }

    /** 结束直播：LIVE → ENDED（状态不符返回 false 不审计）。 */
    @Transactional
    public boolean endLive(String sessionId) {
        LiveSession s = mustGet(sessionId);
        if (!"LIVE".equals(s.getStatus())) {
            return false;
        }
        s.setStatus("ENDED");
        sessionRepo.save(s);
        audit("END", sessionId, Map.of(
                "title", s.getTitle(), "dealCount", s.getDealCount(),
                "dealAmountFen", s.getDealAmount()));
        return true;
    }

    // ==================== 内部方法 ====================

    private LiveSession mustGet(String sessionId) {
        return sessionRepo.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "直播场次不存在：" + sessionId));
    }

    private LocalDateTime parseTime(String s) {
        if (s == null || s.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择开播时间");
        }
        try {
            String v = s.trim().replace('T', ' ');
            return v.length() >= 16 ? LocalDateTime.parse(v.substring(0, 16), DT_FMT)
                    : LocalDateTime.parse(v, DT_FMT);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "开播时间格式不正确（yyyy-MM-dd HH:mm）");
        }
    }

    private List<String> normalizeCodes(List<String> codes) {
        List<String> out = new ArrayList<>();
        if (codes != null) {
            for (String c : codes) {
                if (c != null && !c.isBlank() && !out.contains(c.trim())) {
                    out.add(c.trim());
                }
            }
        }
        return out;
    }

    private String writeJson(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list == null ? List.of() : list);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private void audit(String action, String txnNo, Map<String, Object> payload) {
        try {
            audit.record("LIVE_SESSION", txnNo, DataScope.currentActor(), action,
                    objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            audit.record("LIVE_SESSION", txnNo, DataScope.currentActor(), action, "{}");
        }
    }

    // ==================== 命令 DTO ====================

    /** 创建直播场次命令（startTime：yyyy-MM-dd HH:mm 或 datetime-local 的 yyyy-MM-ddTHH:mm）。 */
    public record SessionCmd(String title, String platform, String startTime,
                             List<String> mountedCouponIds, String intro) {}

    /** 供播种器反序列化挂载券 JSON 复用。 */
    static List<String> readCouponIds(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return new ObjectMapper().readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }
}
