package com.meiyun.ai.chatbot;

import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 客服工作台（A1-07）业务出口。
 * 面向网关业务角色，类级仅需 aiChatbot:view；AI 回复能否真正出站由 chatbot 功能的
 * ai_feature_role 角色灰度矩阵与 ai_feature_binding 门店灰度在 FeatureInvokeService 内判定，
 * 新建会话/转人工动作经审计链留痕。会话与消息全部来自真实操作，空库即工作台诚实空态。
 */
@RestController
@RequestMapping("/api/ai/chatbot")
@RequirePerm("aiChatbot:view")
public class ChatbotController {

    private final ChatbotService chatbotService;

    public ChatbotController(ChatbotService chatbotService) {
        this.chatbotService = chatbotService;
    }

    /** 最近会话列表：channel=all/ai/human 过滤；空库返回空列表走诚实空态。 */
    @GetMapping("/sessions")
    public List<ChatbotService.SessionView> sessions(@RequestParam(required = false) String channel) {
        return chatbotService.sessions(channel);
    }

    /** 新建会话（录入顾客称呼），可带首条顾客消息并同步生成 AI 真实回复。 */
    @PostMapping("/sessions")
    public ChatbotService.SessionDetail createSession(@RequestBody(required = false) ChatbotService.CreateSessionCmd cmd) {
        return chatbotService.createSession(cmd);
    }

    /** 会话详情：消息时间线（append-only），打开即清未读。 */
    @GetMapping("/sessions/{id}")
    public ChatbotService.SessionDetail session(@PathVariable Long id) {
        return chatbotService.openSession(id);
    }

    /** 顾客发问：AI 接待会话同步走 invoke 全治理链返回 AI 回复；人工接待仅落顾客消息。 */
    @PostMapping("/sessions/{id}/messages")
    public ChatbotService.SessionDetail customerMessage(@PathVariable Long id,
                                                        @RequestBody(required = false) ChatbotService.MessageCmd cmd) {
        return chatbotService.customerMessage(id, cmd);
    }

    /** 转人工：仅 AI 接待会话可翻转（幂等），站内登记，M4-09 工作台真实联动为远期 Backlog。 */
    @PostMapping("/sessions/{id}/transfer")
    public ChatbotService.ActionResult transfer(@PathVariable Long id) {
        return chatbotService.transfer(id);
    }

    /** 人工座席回复：仅已转人工的会话可用，落 staff 消息并清未读。 */
    @PostMapping("/sessions/{id}/staff-reply")
    public ChatbotService.SessionDetail staffReply(@PathVariable Long id,
                                                   @RequestBody(required = false) ChatbotService.MessageCmd cmd) {
        return chatbotService.staffReply(id, cmd);
    }

    /** 页头 KPI：今日会话/AI 独立解决/转人工/平均响应（真实计数与环比）+ 本周 chatbot invoke。 */
    @GetMapping("/stats")
    public ChatbotService.ChatbotStats stats() {
        return chatbotService.stats();
    }
}
