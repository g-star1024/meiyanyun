package com.meiyun.marketing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.common.ratelimit.RateLimiter;
import com.meiyun.security.RequirePerm;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 落地页公开采集端点 + 触点监控端点（P5-B98 / DESIGN-T2 §3-D4/D8）。
 *
 * <p><b>公开采集（免鉴权）：</b>
 * <ul>
 *   <li>{@code POST /api/public/landing/{clientToken}/visit|lead}——经 public-paths 白名单
 *       （拦截器 excludePathPatterns 整体不挂载，规避过期 Bearer 误 401）＋网关 /api/public→8088 放行。</li>
 *   <li>存在性隐匿：pageId 必填，页面不存在或 clientToken 不匹配一律 404（不泄露页面存在性）。</li>
 *   <li>限流：单 IP 每分钟 {@value #RATE_LIMIT} 次（RateLimiter 抽象，DB 默认降级 / Redis 生产生效，照 channel-cb 先例）。</li>
 *   <li>幂等（D8 idemKey 范式）：(client_token,touch_type) 查重命中返 dedup=true 不重复计；
 *       并发撞部分唯一索引整事务回滚捕异常返 dedup=true（touch_event 与 visits/leads 自增同滚，不重不计）。</li>
 *   <li>同事务联动：touch_event 落库 + landing_page visits/leads 原子自增（@Modifying UPDATE，防 lost update）。</li>
 * </ul>
 *
 * <p><b>监控（鉴权 @RequirePerm("collect:view"，PermissionMatrix 四码预埋零新码）：</b>
 * T2-01 采集页数据源＝{@code GET /api/marketing/touch-events/summary} 五通道聚合；
 * 同步任务＝{@code GET /api/marketing/touch-events/recent} 触点时间线倒序。
 */
@RestController
public class PublicLandingController {

    private static final Logger log = LoggerFactory.getLogger(PublicLandingController.class);

    private static final int RATE_LIMIT = 60;
    private static final int RATE_WINDOW_SECONDS = 60;
    private static final int BODY_MAX_LEN = 16 * 1024;
    private static final int RECENT_MAX = 200;

    /** 业务日界（与前端 shDateStr 同口径 Asia/Shanghai）：summary「今日触点」统计界。 */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    /** 触点类型五值固定序（监控五通道聚合一行一通道，零计数通道显 0 不伪造状态）。 */
    private static final List<String> TOUCH_TYPES = List.of(
            TouchEventRecorder.TYPE_LANDING_VISIT,
            TouchEventRecorder.TYPE_LANDING_LEAD,
            TouchEventRecorder.TYPE_POSTER_SCAN,
            TouchEventRecorder.TYPE_PUSH_SEND,
            TouchEventRecorder.TYPE_RETURNBACK);

    private final LandingPageRepository landingPageRepository;
    private final TouchEventRepository touchEventRepository;
    private final TouchEventRecorder touchRecorder;
    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PublicLandingController(LandingPageRepository landingPageRepository,
                                   TouchEventRepository touchEventRepository,
                                   TouchEventRecorder touchRecorder,
                                   RateLimiter rateLimiter) {
        this.landingPageRepository = landingPageRepository;
        this.touchEventRepository = touchEventRepository;
        this.touchRecorder = touchRecorder;
        this.rateLimiter = rateLimiter;
    }

    /** 落地页访问采集（C 端/外链访客，免鉴权）。 */
    @PostMapping("/api/public/landing/{clientToken}/visit")
    public Map<String, Object> visit(@PathVariable String clientToken,
                                     @RequestBody(required = false) String rawBody,
                                     HttpServletRequest request) {
        return collect(clientToken, TouchEventRecorder.TYPE_LANDING_VISIT, rawBody, request);
    }

    /** 落地页留资采集（表单提交，免鉴权）。 */
    @PostMapping("/api/public/landing/{clientToken}/lead")
    public Map<String, Object> lead(@PathVariable String clientToken,
                                    @RequestBody(required = false) String rawBody,
                                    HttpServletRequest request) {
        return collect(clientToken, TouchEventRecorder.TYPE_LANDING_LEAD, rawBody, request);
    }

    /** T2-01 数据源：五通道聚合（touch_type 分组计数＋最近触点时刻＋状态）。 */
    @GetMapping("/api/marketing/touch-events/summary")
    @RequirePerm("collect:view")
    public Map<String, Object> summary() {
        OffsetDateTime todayStart = LocalDate.now(BUSINESS_ZONE).atStartOfDay(BUSINESS_ZONE).toOffsetDateTime();
        Map<String, TouchEventRepository.TouchTypeSummary> byType = new LinkedHashMap<>();
        for (TouchEventRepository.TouchTypeSummary row : touchEventRepository.summarizeByType(todayStart)) {
            byType.put(row.getTouchType(), row);
        }
        List<Map<String, Object>> channels = new ArrayList<>();
        long total = 0;
        for (String type : TOUCH_TYPES) {
            TouchEventRepository.TouchTypeSummary row = byType.get(type);
            long cnt = row == null ? 0 : row.getCnt();
            total += cnt;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("touchType", type);
            m.put("count", cnt);
            m.put("todayCount", row == null ? 0L : row.getTodayCnt());
            m.put("latestAt", row == null ? null : row.getLatestAt());
            m.put("status", row == null ? "EMPTY" : "ACTIVE");
            channels.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("channels", channels);
        out.put("total", total);
        return out;
    }

    /** T2-01 同步任务：触点时间线倒序（limit 参数，上限 {@value #RECENT_MAX}）。 */
    @GetMapping("/api/marketing/touch-events/recent")
    @RequirePerm("collect:view")
    public List<TouchEvent> recent(@RequestParam(required = false, defaultValue = "50") int limit) {
        int capped = Math.min(Math.max(limit, 1), RECENT_MAX);
        return touchEventRepository.findAllByOrderByAtDesc(PageRequest.of(0, capped));
    }

    // ==================== 内部 ====================

    private Map<String, Object> collect(String clientToken, String touchType, String rawBody,
                                        HttpServletRequest request) {
        String clientIp = clientIp(request);
        if (!rateLimiter.tryAcquire("public:landing:" + clientIp, RATE_LIMIT, RATE_WINDOW_SECONDS)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "采集请求过于频繁，请稍后再试");
        }
        String token = clientToken == null ? "" : clientToken.trim();
        if (token.isEmpty() || token.length() > 64) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "页面不存在");
        }
        if (rawBody == null || rawBody.isBlank() || rawBody.length() > BODY_MAX_LEN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不可为空且长度需 ≤ 16KB");
        }
        Map<String, Object> body = parseBody(rawBody);
        Object pid = body.get("pageId");
        String pageId = pid == null ? "" : pid.toString().trim();
        if (pageId.isEmpty() || pageId.length() > 24) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pageId 必填且长度需 ≤ 24");
        }
        LandingPage page = landingPageRepository.findById(pageId).orElse(null);
        if (page == null || page.getClientToken() == null || !page.getClientToken().equals(token)) {
            // 存在性隐匿：页面不存在与令牌不匹配同 404，不泄露 pageId 是否真实存在
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "页面不存在");
        }

        String ua = request.getHeader("User-Agent");
        if (ua != null && !ua.isBlank()) {
            body.put("ua", ua.length() > 256 ? ua.substring(0, 256) : ua);
        }
        String referer = request.getHeader("Referer");
        if (referer != null && !referer.isBlank()) {
            body.put("referer", referer.length() > 256 ? referer.substring(0, 256) : referer);
        }
        String payload = toJson(body);

        boolean inserted;
        try {
            inserted = touchRecorder.recordLanding(touchType, pageId, token, payload);
        } catch (DataIntegrityViolationException dup) {
            // 并发双请求同过查重：唯一索引兜底整事务回滚（touch_event+自增同滚），按重放应答
            log.info("落地页采集并发幂等命中：type={} pageId={} clientToken={}", touchType, pageId, token);
            inserted = false;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("received", true);
        out.put("dedup", !inserted);
        out.put("touchType", touchType);
        out.put("pageId", pageId);
        return out;
    }

    private Map<String, Object> parseBody(String rawBody) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(rawBody, Map.class);
            return body;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不是合法 JSON");
        }
    }

    private String toJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不是合法 JSON");
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
