package com.meiyun.marketing.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.marketing.IntegrationConfigClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 存储路由门面（棒⑧卡4）：按 org 集成目录 60s 快照运行时裁决 local / MinIO 直连。
 *
 * <p>STORAGE_DIRECT（SWITCH）未启用 → 本地磁盘兜底（storage.local.root），对存量功能零影响；
 * 启用 → 解析 config_json 六字段（provider/endpoint/bucket/region/accessKey＋选填 publicEndpoint
 * 外轨，F3 卡2 ㉕）＋STORAGE_SECRET 密钥，
 * 任一必填缺失或非法 → 503 SKIPPED 中文诚实降级，不静默回落 local（避免运维误以为已上对象存储）。
 * MinioClient 懒装配，config hash 变化即重建；env（MEIYUN_STORAGE_DIRECT_*）为库外兜底。
 *
 * <p><b>tenant-ctx 链口径（㉕ 登记）：</b>租户/集团上下文（X-Tenant-Id、JWT group claim）
 * 不经存储链路传递——隔离边界由 bucket 按库隔离（meiyun-core/meiyun-seed，V90 在案）＋
 * 后端中介读写（render-file 流式回源走鉴权链）承担；预签名 URL 仅外轨 host 重写，
 * 同 bucket 同 objectKey 签名绑 host 不绑租户，不越隔离边界。
 *
 * <p>读侧按定位符 bucket 回源路由（契约③）：local 固定桶 → 本地腿，其余 → S3 腿；
 * STORAGE_DIRECT 开关切换后历史渲染产物仍可回源，旧图不丢。
 */
@Service
@Primary
public class DelegatingStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(DelegatingStorageService.class);

    /** local 兜底路固定桶名（V90 remark 在案：本地兜底时 bucket 为固定本地桶名）。 */
    public static final String LOCAL_BUCKET = "meiyun-poster-local";

    private static final String CODE_SWITCH = "STORAGE_DIRECT";
    private static final String CODE_SECRET = "STORAGE_SECRET";

    private final IntegrationConfigClient configClient;
    private final LocalStorageService local;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${meiyun.storage.direct.enabled:false}")
    private boolean envEnabled;
    @Value("${meiyun.storage.direct.config:}")
    private String envConfig;
    @Value("${meiyun.storage.direct.secret:}")
    private String envSecret;

    private volatile String s3Hash;
    private volatile S3StorageService s3;
    private volatile String s3Bucket;

    public DelegatingStorageService(IntegrationConfigClient configClient, LocalStorageService local) {
        this.configClient = configClient;
        this.local = local;
    }

    /** 当前路由快照：存储实现 + 该路应使用的 bucket + provider 标识（local/minio）。 */
    public record Route(StorageService service, String bucket, String provider) {
    }

    public Route currentRoute() {
        if (!directEnabled()) {
            return new Route(local, LOCAL_BUCKET, "local");
        }
        S3StorageService s = s3();
        return new Route(s, s3Bucket, "minio");
    }

    @Override
    public String upload(String bucket, String objectKey, InputStream data, long contentLength, String contentType) {
        return currentRoute().service().upload(bucket, objectKey, data, contentLength, contentType);
    }

    @Override
    public InputStream download(String bucket, String objectKey) {
        return routeForRead(bucket).download(bucket, objectKey);
    }

    @Override
    public void delete(String bucket, String objectKey) {
        routeForRead(bucket).delete(bucket, objectKey);
    }

    @Override
    public URL presignedUrl(String bucket, String objectKey, int expireMinutes) {
        return routeForRead(bucket).presignedUrl(bucket, objectKey, expireMinutes);
    }

    /** 读侧按定位符 bucket 回源：local 固定桶 → 本地腿（开关切换后历史产物不丢），其余 → S3 腿。 */
    private StorageService routeForRead(String bucket) {
        if (LOCAL_BUCKET.equals(bucket)) {
            return local;
        }
        return s3();
    }

    private boolean directEnabled() {
        return configClient.resolveSwitch(CODE_SWITCH, envEnabled);
    }

    private S3StorageService s3() {
        S3Config cfg = parseConfig(configClient.resolveConfigJson(CODE_SWITCH, envConfig));
        String secret = configClient.resolveSecret(CODE_SECRET, envSecret);
        if (secret == null || secret.isBlank()) {
            throw skipped("STORAGE_SECRET 密钥未配置");
        }
        String hash = sha256(cfg.endpoint() + "|" + nullToEmpty(cfg.publicEndpoint()) + "|" + cfg.bucket()
                + "|" + cfg.region() + "|" + cfg.accessKey() + "|" + secret);
        if (s3 == null || !hash.equals(s3Hash)) {
            synchronized (this) {
                if (s3 == null || !hash.equals(s3Hash)) {
                    s3 = new S3StorageService(cfg.endpoint(), cfg.publicEndpoint(), cfg.region(), cfg.accessKey(), secret);
                    s3Hash = hash;
                    s3Bucket = cfg.bucket();
                    log.info("对象存储直连 client 已装配 endpoint={} publicEndpoint={} bucket={}",
                            cfg.endpoint(), cfg.publicEndpoint() == null ? "(缺省=内轨)" : cfg.publicEndpoint(), cfg.bucket());
                }
            }
        }
        return s3;
    }

    private S3Config parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            throw skipped("STORAGE_DIRECT 扩展参数未配置");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(configJson);
        } catch (Exception e) {
            throw skipped("STORAGE_DIRECT 扩展参数不是合法 JSON");
        }
        String provider = text(node, "provider");
        String endpoint = text(node, "endpoint");
        String publicEndpoint = text(node, "publicEndpoint");
        String bucket = text(node, "bucket");
        String region = text(node, "region");
        String accessKey = text(node, "accessKey");
        if (provider == null || !provider.equalsIgnoreCase("minio")) {
            throw skipped("STORAGE_DIRECT 扩展参数 provider 缺失或不支持（当前仅支持 minio）");
        }
        if (endpoint == null) {
            throw skipped("STORAGE_DIRECT 扩展参数 endpoint 未配置");
        }
        // publicEndpoint 选填（㉕ 外轨）：缺省=内轨 endpoint，仅预签名 URL 生成使用
        if (bucket == null) {
            throw skipped("STORAGE_DIRECT 扩展参数 bucket 未配置（按库隔离：meiyun-core / meiyun-seed）");
        }
        if (region == null) {
            throw skipped("STORAGE_DIRECT 扩展参数 region 未配置");
        }
        if (accessKey == null) {
            throw skipped("STORAGE_DIRECT 扩展参数 accessKey 未配置");
        }
        return new S3Config(endpoint, publicEndpoint, bucket, region, accessKey);
    }

    private static ResponseStatusException skipped(String reason) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "对象存储已启用但配置不完整，渲染上传暂不可用（SKIPPED）：" + reason);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            return null;
        }
        return v.asText();
    }

    private static String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record S3Config(String endpoint, String publicEndpoint, String bucket, String region, String accessKey) {
    }
}
