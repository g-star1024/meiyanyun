package com.meiyun.customer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.customer.CustomerService.BadReq;
import com.meiyun.customer.CustomerService.Conflict;
import com.meiyun.customer.CustomerService.NotFound;
import com.meiyun.customer.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import com.meiyun.security.SecurityContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * T2-B3 数据服务目录服务（DESIGN-T2 T2-04，/api/customer/t2/dataservice）。
 * 状态机（与前端 stores/t2DataService.ts 逐字对齐）：publish 仅 DRAFT/DEPRECATED；
 * deprecate 仅 PUBLISHED；approve/reject 仅 PENDING——违反一律 409 中文透出当前态
 * （幂等提交方据此识别已流转，照 T2-B2 口径）。
 * publish 后端化版本跃迁 v0.x→v1.0（mock publishService L115 口径，非 v0 开头保持不变）。
 * apply 服务不存在 404 透出 mock 原文「服务不存在」；applicant/decidedBy 一律
 * DataScope.currentActor()（请求体不收 actor，防伪造）；owner 展示用登录人姓名（govern 先例）。
 * 审计 DATA_SERVICE / DSVC-{serviceId} 六动作 CREATE/PUBLISH/DEPRECATE/APPLY/APPROVE/REJECT
 * （同一服务全动作同链，照 T2-B2 TAG_FACTORY 先例）。
 */
@Service
public class DataServiceService {

    private static final Set<String> TYPES = Set.of(DataService.TYPE_API, DataService.TYPE_DATASET);
    private static final Set<String> METHODS = Set.of("GET", "POST");
    private static final Set<String> PUBLISHABLE = Set.of(
            DataService.STATUS_DRAFT, DataService.STATUS_DEPRECATED);

    private final DataServiceRepository serviceRepository;
    private final DataServicePermissionRepository permissionRepository;
    private final AuditRecorder audit;
    private final ObjectMapper mapper;

    public DataServiceService(DataServiceRepository serviceRepository,
                              DataServicePermissionRepository permissionRepository,
                              AuditRecorder audit, ObjectMapper mapper) {
        this.serviceRepository = serviceRepository;
        this.permissionRepository = permissionRepository;
        this.audit = audit;
        this.mapper = mapper;
    }

    /** 服务视图（字段名对齐前端 DataService 契约）。 */
    public record ServiceView(Long id, String name, String type, String endpoint, String method,
                              String description, String owner, String status, Integer callCount24h,
                              Integer avgLatency, BigDecimal errorRate, List<String> fields,
                              List<String> tags, String version, String createdAt) {}

    /** 权限申请视图（字段名对齐前端 ServicePermission 契约）。 */
    public record PermissionView(Long id, Long serviceId, String serviceName, String applicant,
                                 String reason, String status, String appliedAt, String decidedAt,
                                 String decidedBy) {}

    /** 服务列表：id 升序（种子插入序=前端 mock 数组序）；type 精确过滤＋keyword 名称/描述/端点模糊（大小写不敏感）。 */
    @Transactional(readOnly = true)
    public List<ServiceView> listServices(String type, String keyword) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        return serviceRepository.findAllByOrderByIdAsc().stream()
                .filter(s -> type == null || type.isBlank() || type.equals(s.getType()))
                .filter(s -> kw.isEmpty()
                        || s.getName().toLowerCase().contains(kw)
                        || (s.getDescription() != null && s.getDescription().toLowerCase().contains(kw))
                        || (s.getEndpoint() != null && s.getEndpoint().toLowerCase().contains(kw)))
                .map(this::toView)
                .toList();
    }

    /** 权限申请列表：id 升序。 */
    @Transactional(readOnly = true)
    public List<PermissionView> listPermissions() {
        return permissionRepository.findAllByOrderByIdAsc().stream().map(this::toView).toList();
    }

    /** 新建服务：名称必填；类型/请求方式词表校验；缺省 DRAFT/v0.1/指标全 0/owner=当前登录人姓名；落 CREATE 审计。 */
    @Transactional
    public ServiceView createService(String name, String type, String endpoint, String method,
                                     String description, List<String> fields, List<String> tags) {
        if (name == null || name.isBlank()) {
            throw new BadReq("服务名称必填");
        }
        if (type == null || !TYPES.contains(type)) {
            throw new BadReq("服务类型不合法，仅支持 API/DATASET");
        }
        if (method != null && !method.isBlank() && !METHODS.contains(method)) {
            throw new BadReq("请求方式不合法，仅支持 GET/POST");
        }
        String actor = DataScope.currentActor();
        DataService s = new DataService();
        s.setName(name.trim());
        s.setType(type);
        s.setEndpoint(endpoint == null || endpoint.isBlank() ? null : endpoint.trim());
        s.setMethod(method == null || method.isBlank() ? null : method);
        s.setDescription(description == null ? "" : description.trim());
        s.setOwner(displayName(actor));
        s.setStatus(DataService.STATUS_DRAFT);
        s.setCallCount24h(0);
        s.setAvgLatency(0);
        s.setErrorRate(BigDecimal.ZERO);
        s.setFields(writeJson(fields == null ? List.<String>of() : fields));
        s.setTags(writeJson(tags == null ? List.<String>of() : tags));
        s.setVersion("v0.1");
        DataService saved = serviceRepository.save(s);
        audit.record("DATA_SERVICE", "DSVC-" + saved.getId(), actor, "CREATE",
                "{\"name\":\"" + esc(saved.getName()) + "\",\"type\":\"" + saved.getType() + "\"}");
        return toView(saved);
    }

    /** 发布：仅 DRAFT/DEPRECATED；版本 v0.x→v1.0（mock 口径，非 v0 开头保持不变）；落 PUBLISH 审计。 */
    @Transactional
    public ServiceView publishService(Long id) {
        DataService s = mustGet(id);
        mustIn(s, PUBLISHABLE);
        s.setStatus(DataService.STATUS_PUBLISHED);
        if (s.getVersion() != null && s.getVersion().startsWith("v0")) {
            s.setVersion("v1.0");
        }
        String actor = DataScope.currentActor();
        DataService saved = serviceRepository.save(s);
        audit.record("DATA_SERVICE", "DSVC-" + saved.getId(), actor, "PUBLISH",
                "{\"name\":\"" + esc(saved.getName()) + "\",\"version\":\"" + esc(saved.getVersion()) + "\"}");
        return toView(saved);
    }

    /** 下线：仅 PUBLISHED→DEPRECATED；落 DEPRECATE 审计。 */
    @Transactional
    public ServiceView deprecateService(Long id) {
        DataService s = mustGet(id);
        mustIn(s, Set.of(DataService.STATUS_PUBLISHED));
        s.setStatus(DataService.STATUS_DEPRECATED);
        String actor = DataScope.currentActor();
        DataService saved = serviceRepository.save(s);
        audit.record("DATA_SERVICE", "DSVC-" + saved.getId(), actor, "DEPRECATE",
                "{\"name\":\"" + esc(saved.getName()) + "\"}");
        return toView(saved);
    }

    /** 申请权限：服务不存在 404 透出 mock 原文「服务不存在」；applicant=当前操作人；PENDING 落库；落 APPLY 审计。 */
    @Transactional
    public PermissionView applyPermission(Long serviceId, String reason) {
        DataService s = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new NotFound("服务不存在"));
        String actor = DataScope.currentActor();
        DataServicePermission p = new DataServicePermission();
        p.setServiceId(s.getId());
        p.setServiceName(s.getName());
        p.setApplicant(displayName(actor));
        p.setReason(reason == null ? "" : reason.trim());
        p.setStatus(DataServicePermission.STATUS_PENDING);
        p.setAppliedAt(OffsetDateTime.now());
        DataServicePermission saved = permissionRepository.save(p);
        audit.record("DATA_SERVICE", "DSVC-" + s.getId(), actor, "APPLY",
                "{\"name\":\"" + esc(s.getName()) + "\",\"permId\":" + saved.getId() + "}");
        return toView(saved);
    }

    /** 审批通过：仅 PENDING；decidedAt/decidedBy=当前操作人；落 APPROVE 审计。 */
    @Transactional
    public PermissionView approvePermission(Long id) {
        DataServicePermission p = mustGetPerm(id);
        mustInPerm(p, Set.of(DataServicePermission.STATUS_PENDING));
        String actor = DataScope.currentActor();
        p.setStatus(DataServicePermission.STATUS_APPROVED);
        p.setDecidedAt(OffsetDateTime.now());
        p.setDecidedBy(displayName(actor));
        DataServicePermission saved = permissionRepository.save(p);
        audit.record("DATA_SERVICE", "DSVC-" + saved.getServiceId(), actor, "APPROVE",
                "{\"name\":\"" + esc(saved.getServiceName()) + "\",\"applicant\":\""
                        + esc(saved.getApplicant()) + "\"}");
        return toView(saved);
    }

    /** 审批驳回：仅 PENDING；decidedAt/decidedBy=当前操作人；落 REJECT 审计。 */
    @Transactional
    public PermissionView rejectPermission(Long id) {
        DataServicePermission p = mustGetPerm(id);
        mustInPerm(p, Set.of(DataServicePermission.STATUS_PENDING));
        String actor = DataScope.currentActor();
        p.setStatus(DataServicePermission.STATUS_REJECTED);
        p.setDecidedAt(OffsetDateTime.now());
        p.setDecidedBy(displayName(actor));
        DataServicePermission saved = permissionRepository.save(p);
        audit.record("DATA_SERVICE", "DSVC-" + saved.getServiceId(), actor, "REJECT",
                "{\"name\":\"" + esc(saved.getServiceName()) + "\",\"applicant\":\""
                        + esc(saved.getApplicant()) + "\"}");
        return toView(saved);
    }

    private DataService mustGet(Long id) {
        return serviceRepository.findById(id).orElseThrow(() -> new NotFound("服务不存在"));
    }

    private DataServicePermission mustGetPerm(Long id) {
        return permissionRepository.findById(id).orElseThrow(() -> new NotFound("权限申请不存在"));
    }

    /** 状态机前置校验：违反→409 中文透出当前态（幂等提交方据此识别已流转）。 */
    private static void mustIn(DataService s, Set<String> allowed) {
        if (!allowed.contains(s.getStatus())) {
            throw new Conflict("当前状态「" + statusLabel(s.getStatus()) + "」不允许此操作");
        }
    }

    private static void mustInPerm(DataServicePermission p, Set<String> allowed) {
        if (!allowed.contains(p.getStatus())) {
            throw new Conflict("当前状态「" + permStatusLabel(p.getStatus()) + "」不允许此操作");
        }
    }

    private ServiceView toView(DataService s) {
        return new ServiceView(
                s.getId(),
                s.getName(),
                s.getType(),
                s.getEndpoint(),
                s.getMethod(),
                s.getDescription() == null ? "" : s.getDescription(),
                s.getOwner(),
                s.getStatus(),
                s.getCallCount24h() == null ? 0 : s.getCallCount24h(),
                s.getAvgLatency() == null ? 0 : s.getAvgLatency(),
                s.getErrorRate() == null ? BigDecimal.ZERO : s.getErrorRate(),
                parseStrings(s.getFields()),
                parseStrings(s.getTags()),
                s.getVersion(),
                s.getCreatedAt() == null ? "" : s.getCreatedAt().toString());
    }

    private PermissionView toView(DataServicePermission p) {
        return new PermissionView(
                p.getId(),
                p.getServiceId(),
                p.getServiceName(),
                p.getApplicant(),
                p.getReason() == null ? "" : p.getReason(),
                p.getStatus(),
                p.getAppliedAt() == null ? "" : p.getAppliedAt().toString(),
                p.getDecidedAt() == null ? null : p.getDecidedAt().toString(),
                p.getDecidedBy());
    }

    private List<String> parseStrings(String json) {
        try {
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            return mapper.readValue(json, new TypeReference<ArrayList<String>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 展示用姓名：登录人姓名优先，缺省回退工号（govern createRule 先例）。 */
    private static String displayName(String actor) {
        LoginUser u = SecurityContext.get();
        return (u != null && u.staffName() != null && !u.staffName().isBlank()) ? u.staffName() : actor;
    }

    /** 状态中文标签（4xx 报错透出当前态，便于前端/运维直读）。 */
    private static String statusLabel(String status) {
        return switch (status == null ? "" : status) {
            case DataService.STATUS_PUBLISHED -> "已发布";
            case DataService.STATUS_DRAFT -> "草稿";
            case DataService.STATUS_DEPRECATED -> "已下线";
            default -> status == null ? "未知" : status;
        };
    }

    private static String permStatusLabel(String status) {
        return switch (status == null ? "" : status) {
            case DataServicePermission.STATUS_PENDING -> "待审批";
            case DataServicePermission.STATUS_APPROVED -> "已通过";
            case DataServicePermission.STATUS_REJECTED -> "已拒绝";
            default -> status == null ? "未知" : status;
        };
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
