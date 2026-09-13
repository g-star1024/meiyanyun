package com.meiyun.ai.client;

import com.meiyun.security.AuthInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 智能排班员工池跨服务客户端（不直读 org 表）：组织域
 * {@code /api/org/internal/staff/by-role} 仅返回在职员工（主角色或兼岗命中），以系统身份
 * （X-Internal-Token，internal:name-map）取回。排班只读旁路：组织域不可用 / 单角色调用失败一律
 * 软降级（该角色贡献为空，log.warn），由调用方按空员工池如实生成缺口或引导空态，绝不造员工。
 */
@Component
public class OrgStaffClient {

    private static final Logger log = LoggerFactory.getLogger(OrgStaffClient.class);
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    /**
     * 参与门店排班的角色（与 org 域 staff.role_code 裸码对齐）：店长/咨询师/医生/前台四类；
     * 组织域无独立治疗师/收银编码（基线中治疗师归 DOCTOR、收银归 FRONT_DESK），故不重复枚举。
     */
    public static final List<String> SCHEDULE_ROLES = List.of(
            "STORE_MGR", "CONSULTANT", "DOCTOR", "FRONT_DESK");

    private final RestTemplate restTemplate;

    @Value("${org.service.url:http://127.0.0.1:8086}")
    private String orgBaseUrl;
    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public OrgStaffClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** 按角色列在职员工；任何失败软降级空列表（调用方不造数）。 */
    public List<StaffBrief> listByRole(String roleCode, String storeCode) {
        try {
            UriComponentsBuilder b = UriComponentsBuilder
                    .fromHttpUrl(orgBaseUrl + "/api/org/internal/staff/by-role")
                    .queryParam("roleCode", roleCode);
            if (storeCode != null && !storeCode.isBlank()) {
                b.queryParam("storeCode", storeCode.trim());
            }
            URI uri = b.encode(StandardCharsets.UTF_8).build().toUri();
            ResponseEntity<List<Map<String, Object>>> resp =
                    restTemplate.exchange(uri, HttpMethod.GET, entity(), LIST_TYPE);
            List<Map<String, Object>> body = resp.getBody();
            if (body == null) {
                return List.of();
            }
            List<StaffBrief> out = new ArrayList<>();
            for (Map<String, Object> m : body) {
                String id = str(m.get("staffId"));
                if (id.isBlank()) {
                    continue;
                }
                out.add(new StaffBrief(id, str(m.get("staffName")), str(m.get("primaryRole")),
                        str(m.get("status")), str(m.get("storeCode")), str(m.get("region"))));
            }
            return out;
        } catch (Exception e) {
            log.warn("排班员工池拉取失败（软降级为空）role={} store={}: {}",
                    roleCode, storeCode, e.getMessage());
            return List.of();
        }
    }

    /** 全门店排班员工池：排班角色枚举调用并按工号去重（兼岗不重复），按工号排序。 */
    public List<StaffBrief> listStoreStaff(String storeCode) {
        Map<String, StaffBrief> merged = new LinkedHashMap<>();
        for (String role : SCHEDULE_ROLES) {
            for (StaffBrief s : listByRole(role, storeCode)) {
                merged.putIfAbsent(s.staffId(), s);
            }
        }
        List<StaffBrief> all = new ArrayList<>(merged.values());
        all.sort(Comparator.comparing(StaffBrief::staffId));
        return all;
    }

    private HttpEntity<Void> entity() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
        return new HttpEntity<>(headers);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    /** 排班员工简要档案（by-role 出参读模型）。 */
    public record StaffBrief(String staffId, String staffName, String primaryRole,
                             String status, String storeCode, String region) {
    }
}
