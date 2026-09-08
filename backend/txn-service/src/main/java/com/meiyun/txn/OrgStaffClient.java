package com.meiyun.txn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.security.AuthInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * txn → org 服务间客户端（B18 划扣双签复核人硬校验）：复核人必须为真实在职员工，
 * 角色闸门按划扣金额分级——L1（&lt;¥5,000）持 writeoff:create 角色互签（医生/操作师/前台/店长），
 * L2/L3 须店长（L3 前端另强提示区域经理）。
 *
 * <p>与 {@link TxnStaffNameResolver} 的只读降级不同：双签是合规红线，org 不可用 / 超时 / 5xx
 * 一律 502 硬失败（未执行划扣、事务回滚），绝不放行；工号不存在（404）、离职（status=离职）
 * 转 400 中文；其余 4xx 透传中文原因。
 */
@Component
public class OrgStaffClient {

    private static final Logger log = LoggerFactory.getLogger(OrgStaffClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** L1 划扣允许互签的角色（与 writeoff:create 权限持有角色对齐）。 */
    public static final List<String> L1_ROLES = List.of("DOCTOR", "OPERATOR", "FRONT_DESK", "STORE_MGR");
    /** L2/L3 划扣复核人必须具备的角色。 */
    public static final String STORE_MGR = "STORE_MGR";

    private final RestTemplate restTemplate;

    @Value("${org.service.url:http://127.0.0.1:8086}")
    private String orgBaseUrl;
    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public OrgStaffClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 按工号查员工档案（存在 + 在职硬校验）。
     * 工号不存在 → 400「复核人工号不存在」；已离职 → 400「复核人已离职」；
     * org 4xx（除 404）→ 透传中文；网络/5xx → 502 硬失败（不降级，未执行划扣）。
     */
    public StaffProfile fetchStaff(String staffId) {
        String id = staffId == null ? "" : staffId.trim();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
            String url = orgBaseUrl + "/api/org/internal/staff/"
                    + URLEncoder.encode(id, StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
            if (body == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "组织服务返回异常，本次双签校验未通过（未执行划扣）");
            }
            String status = str(body.get("status"));
            if ("离职".equals(status)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "复核人已离职：" + str(body.get("staffName")) + "（工号 " + id + "），不可作为双签复核人");
            }
            List<String> roles = new ArrayList<>();
            Object rawRoles = body.get("roles");
            if (rawRoles instanceof List<?> list) {
                for (Object o : list) {
                    if (o != null && !o.toString().isBlank()) roles.add(o.toString());
                }
            }
            return new StaffProfile(str(body.get("staffId")), str(body.get("staffName")),
                    str(body.get("primaryRole")), roles, status, str(body.get("storeCode")));
        } catch (HttpStatusCodeException e) {
            int sc = e.getStatusCode().value();
            if (sc == 404) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "复核人工号不存在：" + id + "，请选择真实在职员工");
            }
            if (sc >= 400 && sc < 500) {
                log.info("复核人校验被 org 拒绝 status={} body={}", sc, e.getResponseBodyAsString());
                throw new ResponseStatusException(HttpStatus.valueOf(sc), extractMessage(e.getResponseBodyAsString()));
            }
            log.error("复核人校验 org 服务端错误 status={} body={}", sc, e.getResponseBodyAsString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "组织服务暂不可用，本次双签校验未通过（未执行划扣），请稍后重试");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("复核人校验 org 调用异常: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "无法连接组织服务，本次双签校验未通过（未执行划扣），请稍后重试");
        }
    }

    /**
     * 按角色列在职员工（B20 审批 SLA 催办目标人解析）：GET org /internal/staff/by-role。
     * 仅返主角色或兼岗命中 roleCode 的在职员工；storeCode/region 为可选收敛过滤（REGION_MGR 由
     * org 侧把门店解析为区域）。与 {@link #fetchStaff} 的合规硬失败不同：催办是通知类旁路动作，
     * org 不可用 / 超时 / 出错一律软降级为空列表（log.warn，本轮不催，下轮定时任务自愈重试），
     * 绝不因催办失败影响审批主链路。
     */
    public List<StaffBrief> listStaffByRole(String roleCode, String storeCode, String region) {
        String role = roleCode == null ? "" : roleCode.trim();
        if (role.isEmpty()) return List.of();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
            String url = orgBaseUrl + "/api/org/internal/staff/by-role?roleCode="
                    + URLEncoder.encode(role, StandardCharsets.UTF_8);
            if (storeCode != null && !storeCode.isBlank()) {
                url += "&storeCode=" + URLEncoder.encode(storeCode.trim(), StandardCharsets.UTF_8);
            }
            if (region != null && !region.isBlank()) {
                url += "&region=" + URLEncoder.encode(region.trim(), StandardCharsets.UTF_8);
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> body = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), List.class).getBody();
            if (body == null) return List.of();
            List<StaffBrief> out = new ArrayList<>();
            for (Map<String, Object> m : body) {
                String id = str(m.get("staffId"));
                if (id.isBlank()) continue;
                out.add(new StaffBrief(id, str(m.get("staffName")),
                        str(m.get("storeCode")), str(m.get("region"))));
            }
            return out;
        } catch (Exception e) {
            log.warn("SLA 催办目标人解析失败（软降级，本轮不催）role={} store={}: {}",
                    role, storeCode, e.getMessage());
            return List.of();
        }
    }

    /** 催办目标人简要档案（工号/姓名/门店/区域）。 */
    public record StaffBrief(String staffId, String staffName, String storeCode, String region) {
    }

    /** 复核人角色闸门：L1 须持 writeoff:create 四角色之一；L2/L3 须店长。不符 → 400 中文（提示层级与所需角色）。 */
    public static void requireReviewerRole(StaffProfile profile, String tier) {
        String reviewerId = profile.staffId();
        List<String> roles = new ArrayList<>(profile.roles());
        if (profile.primaryRole() != null && !profile.primaryRole().isBlank() && !roles.contains(profile.primaryRole())) {
            roles.add(profile.primaryRole());
        }
        if ("L1".equals(tier)) {
            boolean ok = roles.stream().anyMatch(L1_ROLES::contains);
            if (!ok) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "本笔划扣为 L1 级（<¥5,000），复核人须为持划扣权限的员工（医生/操作师/前台/店长）；"
                                + "复核人 " + reviewerId + " 当前角色不满足");
            }
        } else {
            if (!roles.contains(STORE_MGR)) {
                String level = "L2".equals(tier) ? "L2 级（¥5,000~20,000）" : "L3 级（≥¥20,000）";
                String extra = "L3".equals(tier)
                        ? "，并须区域经理知晓（大额划扣请同步报备区域经理）" : "";
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "本笔划扣为" + level + "，复核人须为店长" + extra
                                + "；复核人 " + reviewerId + " 非店长，不可双签");
            }
        }
    }

    /** 复核人档案（org internal staff profile 的 txn 侧读模型）。 */
    public record StaffProfile(String staffId, String staffName, String primaryRole,
                               List<String> roles, String status, String storeCode) {
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    /** 从 org 错误体 {"message":"中文"} 提取中文原因；解析失败回落通用文案。 */
    private static String extractMessage(String body) {
        if (body != null && body.contains("\"message\"")) {
            try {
                String m = MAPPER.readTree(body).path("message").asText(null);
                if (m != null && !m.isBlank()) return m;
            } catch (Exception ignored) {
                // 落到兜底文案
            }
        }
        return "组织服务拒绝了本次复核人校验，请稍后重试";
    }
}
