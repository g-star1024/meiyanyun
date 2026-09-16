package com.meiyun.txn;

import com.meiyun.security.AuthInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;

/**
 * txn → org 员工当日班次客户端（B54 卡2，派单真实可用时间真源）。
 *
 * <p>GET org /api/org/internal/schedule/resolve?staffId=&date=（X-Internal-Token 系统身份）。
 * 软降级口径（铁律 6，旁路非合规红线）：org 不可用 / 超时 / 任意异常，以及该员工当日
 * 无排班行（present=false），一律回落营业窗 09:00-20:00（{@link DispatchService#WORK_START}/
 * {@link DispatchService#WORK_END}），绝不因排班服务抖动阻断既有派单主链路；
 * OFF 休息 / LEAVE 请假为权威业务结论，硬返回不可派（present=true, assignable=false），不降级。
 */
@Component
public class OrgScheduleClient {

    private static final Logger log = LoggerFactory.getLogger(OrgScheduleClient.class);

    private final RestTemplate restTemplate;

    @Value("${org.service.url:http://127.0.0.1:8086}")
    private String orgBaseUrl;
    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public OrgScheduleClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 解析员工当日可派班次窗。永不抛异常：
     * OFF/LEAVE → {@link ShiftWindow#unavailable}；无排班行或调用失败 → 营业窗降级；
     * FULL/MORNING/MID → org 侧算好的钟点窗（全天 09:00-20:00 / 上午 09:00-14:00 / 下午 14:00-20:00）。
     */
    public ShiftWindow resolve(String staffId, LocalDate date) {
        String id = staffId == null ? "" : staffId.trim();
        String day = date == null ? LocalDate.now().toString() : date.toString();
        if (id.isEmpty()) {
            return ShiftWindow.unavailable(id, day, null, null);
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
            String url = orgBaseUrl + "/api/org/internal/schedule/resolve?staffId="
                    + URLEncoder.encode(id, StandardCharsets.UTF_8)
                    + "&date=" + URLEncoder.encode(day, StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
            if (body == null) {
                return fallback(id, day, "org 返回空");
            }
            boolean present = Boolean.TRUE.equals(body.get("present"));
            boolean assignable = Boolean.TRUE.equals(body.get("assignable"));
            String code = str(body.get("shiftCode"));
            String source = str(body.get("source"));
            if (!present) {
                return new ShiftWindow(id, day, null, null, true,
                        DispatchService.WORK_START, DispatchService.WORK_END);
            }
            if (!assignable) {
                return ShiftWindow.unavailable(id, day, code, source);
            }
            String start = str(body.get("windowStart"));
            String end = str(body.get("windowEnd"));
            if (start.isBlank() || end.isBlank()) {
                return fallback(id, day, "org 可派但钟点窗为空");
            }
            return new ShiftWindow(id, day, code, source, false, start, end);
        } catch (Exception e) {
            return fallback(id, day, e.getMessage());
        }
    }

    /** 软降级：营业窗 + degraded=true，记 warn（不阻断派单）。 */
    private ShiftWindow fallback(String id, String day, String reason) {
        log.warn("员工班次解析失败软降级营业窗 staff={} date={}: {}", id, day, reason);
        return new ShiftWindow(id, day, null, null, true,
                DispatchService.WORK_START, DispatchService.WORK_END);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    /**
     * 员工当日班次窗读模型。degraded=true 表示未经排班真源确认（无排班行/服务异常回落营业窗）；
     * assignable=false 时 start/end 为 null（OFF/LEAVE 权威不可派）。
     */
    public record ShiftWindow(String staffId, String date, String shiftCode, String source,
                              boolean degraded, String start, String end) {

        public boolean assignable() {
            return start != null && end != null;
        }

        /** OFF 休息 / LEAVE 请假：权威不可派，不降级。 */
        public static ShiftWindow unavailable(String staffId, String date, String code, String source) {
            return new ShiftWindow(staffId, date, code, source, false, null, null);
        }
    }
}
