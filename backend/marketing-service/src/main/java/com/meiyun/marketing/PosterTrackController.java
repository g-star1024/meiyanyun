package com.meiyun.marketing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.common.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 海报漏斗公开采集端点（棒⑥卡5 / L160②：share/scan/lead/visit 四级真实采集）。
 *
 * <p><b>公开采集（免鉴权）：</b>
 * <ul>
 *   <li>{@code POST /api/public/poster/{posterId}/track}——经 public-paths 白名单
 *       （照 PublicLandingController /api/public/landing/** 先例）＋网关 /api/public 放行。</li>
 *   <li>存在性隐匿：海报不存在一律 404（不泄露 posterId 是否真实存在）。</li>
 *   <li>限流：单 IP 每分钟 {@value #RATE_LIMIT} 次（RateLimiter 抽象，与落地页采集同配额）。</li>
 *   <li>幂等（D8 idemKey 范式）：body.clientToken 必填——(client_token,touch_type) 查重命中
 *       返 dedup=true 不重复计；并发撞部分唯一索引整事务回滚捕异常返 dedup=true
 *       （touch_event 与 poster_record 漏斗列自增同滚，不重不计）。</li>
 *   <li>同事务联动：touch_event 落库（channel=POSTER）＋ poster_record share/scan/lead/visit
 *       原子自增（{@link TouchEventRecorder#recordPoster}）。</li>
 *   <li>deal 成交不经本端点——由 DealBackfillService 成交回写（L160③）。</li>
 * </ul>
 */
@RestController
public class PosterTrackController {

    private static final Logger log = LoggerFactory.getLogger(PosterTrackController.class);

    private static final int RATE_LIMIT = 60;
    private static final int RATE_WINDOW_SECONDS = 60;
    private static final int BODY_MAX_LEN = 16 * 1024;

    private final PosterRecordRepository posterRecordRepository;
    private final TouchEventRecorder touchRecorder;
    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PosterTrackController(PosterRecordRepository posterRecordRepository,
                                 TouchEventRecorder touchRecorder,
                                 RateLimiter rateLimiter) {
        this.posterRecordRepository = posterRecordRepository;
        this.touchRecorder = touchRecorder;
        this.rateLimiter = rateLimiter;
    }

    /** 海报漏斗采集（C 端扫码/留资/到店＋管理端分享上报，免鉴权）。 */
    @PostMapping("/api/public/poster/{posterId}/track")
    public Map<String, Object> track(@PathVariable String posterId,
                                     @RequestBody(required = false) String rawBody,
                                     HttpServletRequest request) {
        String clientIp = clientIp(request);
        if (!rateLimiter.tryAcquire("public:poster:" + clientIp, RATE_LIMIT, RATE_WINDOW_SECONDS)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "采集请求过于频繁，请稍后再试");
        }
        String pid = posterId == null ? "" : posterId.trim();
        if (pid.isEmpty() || pid.length() > 24) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "海报不存在");
        }
        if (rawBody == null || rawBody.isBlank() || rawBody.length() > BODY_MAX_LEN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不可为空且长度需 ≤ 16KB");
        }
        Map<String, Object> body = parseBody(rawBody);

        Object tp = body.get("type");
        String type = tp == null ? "" : tp.toString().trim().toUpperCase();
        String touchType = switch (type) {
            case "SHARE" -> TouchEventRecorder.TYPE_POSTER_SHARE;
            case "SCAN" -> TouchEventRecorder.TYPE_POSTER_SCAN;
            case "LEAD" -> TouchEventRecorder.TYPE_POSTER_LEAD;
            case "VISIT" -> TouchEventRecorder.TYPE_POSTER_VISIT;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "采集类型不合法（可选：SHARE/SCAN/LEAD/VISIT）");
        };

        Object ct = body.get("clientToken");
        String clientToken = ct == null ? "" : ct.toString().trim();
        if (clientToken.isEmpty() || clientToken.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clientToken 必填且长度需 ≤ 64");
        }

        PosterRecord poster = posterRecordRepository.findById(pid).orElse(null);
        if (poster == null) {
            // 存在性隐匿：不泄露 posterId 是否真实存在
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "海报不存在");
        }

        String ua = request.getHeader("User-Agent");
        if (ua != null && !ua.isBlank()) {
            body.put("ua", ua.length() > 256 ? ua.substring(0, 256) : ua);
        }
        String referer = request.getHeader("Referer");
        if (referer != null && !referer.isBlank()) {
            body.put("referer", referer.length() > 256 ? referer.substring(0, 256) : referer);
        }
        body.remove("clientToken");
        String payload = toJson(body);

        boolean inserted;
        try {
            inserted = touchRecorder.recordPoster(touchType, pid, clientToken, payload);
        } catch (DataIntegrityViolationException dup) {
            // 并发双请求同过查重：唯一索引兜底整事务回滚（touch_event+自增同滚），按重放应答
            log.info("海报采集并发幂等命中：type={} posterId={} clientToken={}", touchType, pid, clientToken);
            inserted = false;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("received", true);
        out.put("dedup", !inserted);
        out.put("touchType", touchType);
        out.put("posterId", pid);
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
