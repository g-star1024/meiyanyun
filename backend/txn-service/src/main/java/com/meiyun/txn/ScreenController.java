package com.meiyun.txn;

import com.meiyun.security.DataScope;
import com.meiyun.security.JwtTokenUtil;
import com.meiyun.security.LoginUser;
import com.meiyun.security.RequirePerm;
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
 * M1 集团经营数据大屏（B49 卡7）：快照聚合 + 实时成交流 SSE。
 *
 * <p><b>SSE 握手鉴权（全量复用 NotificationStreamController 模板）：</b>浏览器 EventSource
 * 无法自定义 Authorization 头，因此除标准 Bearer 头外（经 AuthInterceptor 建立 SecurityContext），
 * 额外支持 {@code ?access_token=} 查询参数握手——请求头无身份时解析为局部变量使用，
 * 不写入 SecurityContext（SSE 异步启动后请求线程即归还线程池，ThreadLocal 写入会残留串用户，
 * 详见 AuthInterceptor#afterConcurrentHandlingStarted）。与通知流差异：额外校验 {@code screen:view} 权限
 * （大屏为集团敏感视图，PermissionMatrix：SUPER_ADMIN 通配 + REGION_MGR/STORE_MGR/FINANCE）。
 * 每 25s 发送 heartbeat 注释帧（保活 + 代理超时规避），30 分钟整体超时，前端自动重连。
 */
@RestController
@RequestMapping("/api/txn/screen")
public class ScreenController {

    /** 心跳间隔 25s（小于常见 30s 代理空闲超时）。 */
    private static final long HEARTBEAT_MS = 25_000L;

    private final ScreenService screenService;
    private final ScreenStreamRegistry registry;
    private final JwtTokenUtil jwtTokenUtil;

    public ScreenController(ScreenService screenService, ScreenStreamRegistry registry,
                            JwtTokenUtil jwtTokenUtil) {
        this.screenService = screenService;
        this.registry = registry;
        this.jwtTokenUtil = jwtTokenUtil;
    }

    /**
     * 快照聚合：GET /api/txn/screen/overview。
     * KPI/hourly/品类/排行口径单一来源（前端 30s 定时重取）；金额单位「分」。
     */
    @GetMapping("/overview")
    @RequirePerm("screen:view")
    public ScreenService.ScreenOverviewView overview() {
        return screenService.overview();
    }

    /**
     * 实时成交流：GET /api/txn/screen/stream（SSE）。
     * 建连先发 {@code ready} 事件；新收款流水由 ScreenOrderFanoutJob 推 {@code order-paid} 事件。
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(value = "access_token", required = false) String accessToken) {
        LoginUser me = DataScope.current();
        if (me == null && accessToken != null && !accessToken.isBlank()) {
            try {
                me = jwtTokenUtil.parse(accessToken.trim());
            } catch (JwtTokenUtil.JwtAuthException e) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "SSE 握手凭证无效：" + e.getMessage());
            }
        }
        if (me == null || me.staffId() == null || me.staffId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已失效");
        }
        if (!me.hasPerm("screen:view")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无数据大屏查看权限（screen:view）");
        }
        final String staffId = me.staffId();

        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        registry.add(emitter);

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
        }, "sse-screen-heartbeat-" + staffId);
        heartbeat.setDaemon(true);

        Runnable cleanup = () -> {
            heartbeat.interrupt();
            registry.remove(emitter);
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
