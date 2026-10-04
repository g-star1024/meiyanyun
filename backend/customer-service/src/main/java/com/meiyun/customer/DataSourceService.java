package com.meiyun.customer;

import com.meiyun.customer.CustomerService.BadReq;
import com.meiyun.customer.CustomerService.Conflict;
import com.meiyun.customer.CustomerService.NotFound;
import com.meiyun.customer.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import com.meiyun.security.SecurityContext;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * T2-01 数据源注册服务（棒⑥卡7，/api/customer/t2/datasources）。
 * 注册登记真源化：code 不可变·name/endpoint/description 可编辑；类型三值白名单
 * CDC/KAFKA/THIRD_PARTY；状态机 REGISTERED/CONNECTED→DISABLED（重复禁用 409 透出当前态）。
 * 连通探测 sync：三类型（CDC/KAFKA/THIRD_PARTY）均真实探测（http(s) 走 HttpURLConnection·
 * 其余按 scheme 剥离 host:port Socket，3s 超时；响应码<500 视为通），成功→CONNECTED＋
 * lastSyncAt=now，失败 400 如实透出原因不伪造连通；棒⑧卡5 放开 CDC/KAFKA 探测（接入
 * 装配位/消费运行时归数据中台二期 DESIGN-T3 §7）。owner 展示用登录人姓名
 * （govern 先例）；操作人一律 DataScope.currentActor() 防伪造。
 * 审计 DATA_SOURCE / DS-{id} 四动作 CREATE/EDIT/DISABLE/SYNC（同一源全动作同链，照
 * T2-B3 DATA_SERVICE 先例）。
 */
@Service
public class DataSourceService {

    private static final Set<String> TYPES = Set.of(
            DataSource.TYPE_CDC, DataSource.TYPE_KAFKA, DataSource.TYPE_THIRD_PARTY);
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final Set<String> DISABLEABLE = Set.of(
            DataSource.STATUS_REGISTERED, DataSource.STATUS_CONNECTED);
    private static final int PROBE_TIMEOUT_MS = 3000;

    private final DataSourceRepository repository;
    private final AuditRecorder audit;

    public DataSourceService(DataSourceRepository repository, AuditRecorder audit) {
        this.repository = repository;
        this.audit = audit;
    }

    /** 数据源视图（字段名对齐前端 DataSource 契约）。 */
    public record DataSourceView(Long id, String code, String name, String type, String endpoint,
                                 String description, String status, String owner,
                                 String lastSyncAt, String createdAt, String updatedAt) {}

    /** 列表：id 升序；type 精确过滤＋keyword 名称/编码/端点/描述模糊（大小写不敏感）。 */
    @Transactional(readOnly = true)
    public List<DataSourceView> list(String type, String keyword) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        return repository.findAllByOrderByIdAsc().stream()
                .filter(d -> type == null || type.isBlank() || type.equals(d.getType()))
                .filter(d -> kw.isEmpty()
                        || d.getName().toLowerCase().contains(kw)
                        || d.getCode().toLowerCase().contains(kw)
                        || (d.getEndpoint() != null && d.getEndpoint().toLowerCase().contains(kw))
                        || (d.getDescription() != null && d.getDescription().toLowerCase().contains(kw)))
                .map(DataSourceService::toView)
                .toList();
    }

    /** 新建：编码唯一（存在 409）；类型三值白名单；owner=登录人姓名；初始态 REGISTERED；落 CREATE 审计。 */
    @Transactional
    public DataSourceView create(String code, String name, String type, String endpoint, String description) {
        if (name == null || name.isBlank()) {
            throw new BadReq("数据源名称不能为空");
        }
        if (name.trim().length() > 100) {
            throw new BadReq("数据源名称超长（上限 100 字符）");
        }
        if (code == null || !CODE_PATTERN.matcher(code.trim()).matches()) {
            throw new BadReq("数据源编码须大写字母开头，仅含大写字母/数字/下划线（上限 64 字符）");
        }
        if (type == null || !TYPES.contains(type)) {
            throw new BadReq("数据源类型仅支持 CDC / KAFKA / THIRD_PARTY");
        }
        if (endpoint != null && endpoint.trim().length() > 200) {
            throw new BadReq("接入地址超长（上限 200 字符）");
        }
        if (repository.existsByCode(code.trim())) {
            throw new Conflict("数据源编码「" + code.trim() + "」已存在");
        }
        String actor = DataScope.currentActor();
        DataSource d = new DataSource();
        d.setCode(code.trim());
        d.setName(name.trim());
        d.setType(type);
        d.setEndpoint(endpoint == null || endpoint.isBlank() ? null : endpoint.trim());
        d.setDescription(description == null ? "" : description.trim());
        d.setOwner(displayName(actor));
        DataSource saved = repository.save(d);
        audit.record("DATA_SOURCE", "DS-" + saved.getId(), actor, "CREATE",
                "{\"code\":\"" + esc(saved.getCode()) + "\",\"name\":\"" + esc(saved.getName())
                        + "\",\"type\":\"" + saved.getType() + "\"}");
        return toView(saved);
    }

    /** 编辑：仅 name/endpoint/description（code/type 不可变）；DISABLED 409 透出当前态；落 EDIT 审计。 */
    @Transactional
    public DataSourceView update(Long id, String name, String endpoint, String description) {
        DataSource d = mustGet(id);
        if (!DISABLEABLE.contains(d.getStatus())) {
            throw new Conflict("当前状态「" + statusLabel(d.getStatus()) + "」不允许此操作");
        }
        if (name == null || name.isBlank()) {
            throw new BadReq("数据源名称不能为空");
        }
        if (name.trim().length() > 100) {
            throw new BadReq("数据源名称超长（上限 100 字符）");
        }
        if (endpoint != null && endpoint.trim().length() > 200) {
            throw new BadReq("接入地址超长（上限 200 字符）");
        }
        String actor = DataScope.currentActor();
        d.setName(name.trim());
        d.setEndpoint(endpoint == null || endpoint.isBlank() ? null : endpoint.trim());
        d.setDescription(description == null ? "" : description.trim());
        DataSource saved = repository.save(d);
        audit.record("DATA_SOURCE", "DS-" + saved.getId(), actor, "EDIT",
                "{\"code\":\"" + esc(saved.getCode()) + "\",\"name\":\"" + esc(saved.getName()) + "\"}");
        return toView(saved);
    }

    /** 停用：REGISTERED/CONNECTED→DISABLED（重复禁用 409 透出当前态）；落 DISABLE 审计。 */
    @Transactional
    public DataSourceView disable(Long id) {
        DataSource d = mustGet(id);
        if (!DISABLEABLE.contains(d.getStatus())) {
            throw new Conflict("当前状态「" + statusLabel(d.getStatus()) + "」不允许此操作");
        }
        String actor = DataScope.currentActor();
        d.setStatus(DataSource.STATUS_DISABLED);
        DataSource saved = repository.save(d);
        audit.record("DATA_SOURCE", "DS-" + saved.getId(), actor, "DISABLE",
                "{\"code\":\"" + esc(saved.getCode()) + "\",\"name\":\"" + esc(saved.getName()) + "\"}");
        return toView(saved);
    }

    /**
     * 连通探测：三类型均真实探测（http(s) GET 响应码<500 视为通；其余按 scheme 剥离
     * host:port Socket 连接；超时 3s；棒⑧卡5 放开 CDC/KAFKA，接入装配位归 DESIGN-T3 §7），
     * 成功→CONNECTED＋lastSyncAt=now 落 SYNC 审计；失败 400 如实透出原因；DISABLED 409 透出当前态。
     */
    @Transactional
    public DataSourceView sync(Long id) {
        DataSource d = mustGet(id);
        if (!DISABLEABLE.contains(d.getStatus())) {
            throw new Conflict("当前状态「" + statusLabel(d.getStatus()) + "」不允许此操作");
        }
        if (d.getEndpoint() == null || d.getEndpoint().isBlank()) {
            throw new BadReq("数据源未配置接入地址，无法探测");
        }
        String failure = probe(d.getEndpoint());
        if (failure != null) {
            throw new BadReq("连通探测失败：" + failure);
        }
        String actor = DataScope.currentActor();
        d.setStatus(DataSource.STATUS_CONNECTED);
        d.setLastSyncAt(java.time.OffsetDateTime.now());
        DataSource saved = repository.save(d);
        audit.record("DATA_SOURCE", "DS-" + saved.getId(), actor, "SYNC",
                "{\"code\":\"" + esc(saved.getCode()) + "\",\"endpoint\":\""
                        + esc(saved.getEndpoint()) + "\"}");
        return toView(saved);
    }

    /** 探测返回 null=通，否则失败原因（截 120 字符防超长透出）。 */
    private static String probe(String endpoint) {
        try {
            if (endpoint.regionMatches(true, 0, "http://", 0, 7)
                    || endpoint.regionMatches(true, 0, "https://", 0, 8)) {
                HttpURLConnection conn = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
                try {
                    conn.setConnectTimeout(PROBE_TIMEOUT_MS);
                    conn.setReadTimeout(PROBE_TIMEOUT_MS);
                    conn.setInstanceFollowRedirects(false);
                    conn.setRequestMethod("GET");
                    int code = conn.getResponseCode();
                    return code < 500 ? null : "HTTP " + code;
                } finally {
                    conn.disconnect();
                }
            }
            String hp = endpoint.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
            int slash = hp.indexOf('/');
            if (slash >= 0) {
                hp = hp.substring(0, slash);
            }
            String host;
            int port;
            int colon = hp.lastIndexOf(':');
            if (colon > 0 && colon < hp.length() - 1) {
                host = hp.substring(0, colon);
                port = Integer.parseInt(hp.substring(colon + 1));
            } else {
                host = hp;
                port = 80;
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
            }
            return null;
        } catch (IOException | IllegalArgumentException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return msg.length() > 120 ? msg.substring(0, 120) : msg;
        }
    }

    private DataSource mustGet(Long id) {
        return repository.findById(id).orElseThrow(() -> new NotFound("数据源不存在"));
    }

    private static DataSourceView toView(DataSource d) {
        return new DataSourceView(
                d.getId(),
                d.getCode(),
                d.getName(),
                d.getType(),
                d.getEndpoint(),
                d.getDescription() == null ? "" : d.getDescription(),
                d.getStatus(),
                d.getOwner(),
                d.getLastSyncAt() == null ? null : d.getLastSyncAt().toString(),
                d.getCreatedAt() == null ? "" : d.getCreatedAt().toString(),
                d.getUpdatedAt() == null ? "" : d.getUpdatedAt().toString());
    }

    /** 展示用姓名：登录人姓名优先，缺省回退工号（govern createRule 先例）。 */
    private static String displayName(String actor) {
        LoginUser u = SecurityContext.get();
        return (u != null && u.staffName() != null && !u.staffName().isBlank()) ? u.staffName() : actor;
    }

    /** 状态中文标签（4xx 报错透出当前态，便于前端/运维直读）。 */
    private static String statusLabel(String status) {
        return switch (status == null ? "" : status) {
            case DataSource.STATUS_REGISTERED -> "已注册";
            case DataSource.STATUS_CONNECTED -> "已连通";
            case DataSource.STATUS_DISABLED -> "已停用";
            default -> status == null ? "未知" : status;
        };
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
