package com.meiyun.txn;

import com.meiyun.security.DataScope;
import com.meiyun.security.JwtTokenUtil;
import com.meiyun.security.LoginUser;
import com.meiyun.security.SecurityContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 通知实时流（域⑦ SSE）。前端铃铛订阅 /api/txn/notifications/stream，
 * 新通知经 NotificationFanoutJob 推送给当前登录人工号对应的 emitter，替代轮询。
 *
 * <p><b>握手鉴权：</b>浏览器 EventSource 无法自定义 Authorization 头，因此除标准 Bearer 头外
 * （经 AuthInterceptor 建立 SecurityContext），额外支持 {@code ?access_token=} 查询参数握手——
 * 仅在请求头无身份时解析 query token 并在本请求内临时 set/finally clear，绝不写入共享状态。
 * 每 25s 发送 heartbeat 注释帧（保活 + 代理超时规避），30 分钟整体超时，前端自动重连。
 */
@RestController
@RequestMapping("/api/txn")
public class NotificationStreamController {

    /** 心跳间隔 25s（小于常见 30s 代理空闲超时）。 */
    private static final long HEARTBEAT_MS = 25_000L;

    private final NotificationStreamRegistry registry;
    private final JwtTokenUtil jwtTokenUtil;

    public NotificationStreamController(NotificationStreamRegistry registry, JwtTokenUtil jwtTokenUtil) {
        this.registry = registry;
        this.jwtTokenUtil = jwtTokenUtil;
    }

    @GetMapping(path = "/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(value = "access_token", required = false) String accessToken) {
        LoginUser headerUser = DataScope.current();
        LoginUser queryUser = null;
        if (headerUser == null && accessToken != null && !accessToken.isBlank()) {
            try {
                queryUser = jwtTokenUtil.parse(accessToken.trim());
                SecurityContext.set(queryUser);
            } catch (JwtTokenUtil.JwtAuthException e) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "SSE 握手凭证无效：" + e.getMessage());
            }
        }
        final LoginUser me = DataScope.current();
        if (me == null || me.staffId() == null || me.staffId().isBlank()) {
            if (queryUser != null) SecurityContext.clear();
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已失效");
        }
        final boolean clearContext = queryUser != null;
        final String staffId = me.staffId();

        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        registry.add(staffId, emitter);

        Thread heartbeat = new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(HEARTBEAT_MS);
                    try {
                        emitter.send(SseEmitter.event().comment("hb"));
                    } catch (IOException | IllegalStateException gone) {
                        return;
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }, "sse-heartbeat-" + staffId);
        heartbeat.setDaemon(true);

        Runnable cleanup = () -> {
            heartbeat.interrupt();
            registry.remove(staffId, emitter);
            if (clearContext) SecurityContext.clear();
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        try {
            emitter.send(SseEmitter.event().name("ready").data("{\"staffId\":\"" + staffId + "\"}"));
        } catch (IOException e) {
            cleanup.run();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "SSE 建立失败");
        }
        heartbeat.start();
        return emitter;
    }
}
