package com.meiyun.marketing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.marketing.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 营销素材库写链路（M5-13）：上传 / 标签增删 / 授权分发。
 *
 * <p>写接口四件套：① 参数校验（类型/范围/视觉色白名单、名称/有效期必填、COPY 文案合规）；
 * ② 幂等（标签已存在/已授权门店不重复落库、不重复审计）；③ 全动作审计（bizType=MARKETING_ASSET，
 * payload 为合法 JSON）；④ 中文错误（ResponseStatusException 400/404）。
 * tags / storeCodes 以 JSON 文本数组落库；门店编码经 store-service name-map 校验（异常降级放行）。
 */
@Service
public class MarketingAssetService {

    public static final List<String> TYPES = List.of("IMAGE", "VIDEO", "COPY", "LOGO");
    public static final List<String> SCOPES = List.of("ALL", "SPECIFIED");
    public static final List<String> ACCENTS = List.of("brand", "teal", "orange", "purple", "blue", "gold");

    private final MarketingAssetRepository repo;
    private final BizNoGenerator noGen;
    private final AuditRecorder audit;
    private final ForbiddenWordService forbiddenWordService;
    private final StoreNameResolver storeNameResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MarketingAssetService(MarketingAssetRepository repo, BizNoGenerator noGen, AuditRecorder audit,
                                 ForbiddenWordService forbiddenWordService, StoreNameResolver storeNameResolver) {
        this.repo = repo;
        this.noGen = noGen;
        this.audit = audit;
        this.forbiddenWordService = forbiddenWordService;
        this.storeNameResolver = storeNameResolver;
    }

    // ==================== 查询 ====================

    public List<MarketingAsset> list() {
        return repo.findAll();
    }

    // ==================== 写动作 ====================

    /** 上传素材（tags 去重去空；scope=ALL 时门店授权存空数组，由前端按门店主数据展开）。 */
    @Transactional
    public MarketingAsset upload(AssetCmd cmd) {
        String name = cmd.name() == null ? "" : cmd.name().trim();
        String type = cmd.type() == null ? "" : cmd.type().trim();
        String scope = cmd.scope() == null ? "ALL" : cmd.scope().trim();
        String accent = cmd.accent() == null ? "brand" : cmd.accent().trim();
        if (name.isEmpty() || name.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "素材名称不可为空且长度不超过 64 字");
        }
        if (!TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "素材类型不合法（IMAGE/VIDEO/COPY/LOGO）");
        }
        if (!SCOPES.contains(scope)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "授权范围不合法（ALL/SPECIFIED）");
        }
        if (!ACCENTS.contains(accent)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "视觉色不合法");
        }
        LocalDate expireAt = parseDate(cmd.expireAt());
        if (expireAt == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择素材有效期");
        }
        List<String> tags = normalizeTags(cmd.tags());
        if (tags.size() > 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单个素材标签不超过 10 个");
        }
        String content = cmd.content() == null ? null : cmd.content().trim();
        if ("COPY".equals(type)) {
            if (content == null || content.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文案素材请填写正文内容");
            }
            List<String> hits = forbiddenWordService.check(name + "\n" + content);
            if (!hits.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "营销合规拦截：命中违禁词 " + String.join("、", hits));
            }
        }
        List<String> storeCodes = "SPECIFIED".equals(scope) ? normalizeCodes(cmd.storeCodes()) : List.of();
        if ("SPECIFIED".equals(scope) && storeCodes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "指定门店授权时请至少选择 1 家门店");
        }
        validateStoreCodes(storeCodes);

        MarketingAsset a = new MarketingAsset();
        a.setAssetId(noGen.next("AS", like -> repo
                .findTopByAssetIdLikeOrderByAssetIdDesc(like).map(MarketingAsset::getAssetId).orElse(null)));
        a.setAssetName(name);
        a.setType(type);
        a.setTags(writeJson(tags));
        a.setScope(scope);
        a.setStoreCodes(writeJson(storeCodes));
        a.setExpireAt(expireAt);
        a.setRefCount(0);
        a.setAccent(accent);
        a.setContent(content);
        a.setCreatedAt(OffsetDateTime.now());
        MarketingAsset saved = repo.save(a);
        audit("UPLOAD", saved.getAssetId(), Map.of(
                "name", name, "type", type, "scope", scope,
                "tags", tags, "storeCodes", storeCodes));
        return saved;
    }

    /** 新增标签（幂等：已存在返回 false 不审计）。 */
    @Transactional
    public boolean addTag(String assetId, String tag) {
        MarketingAsset a = mustGet(assetId);
        String t = tag == null ? "" : tag.trim();
        if (t.isEmpty() || t.length() > 16) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标签不可为空且长度不超过 16 字");
        }
        List<String> tags = readTags(a);
        if (tags.contains(t)) {
            return false;
        }
        tags.add(t);
        a.setTags(writeJson(tags));
        repo.save(a);
        audit("ADD_TAG", assetId, Map.of("name", a.getAssetName(), "tag", t, "tags", tags));
        return true;
    }

    /** 删除标签（幂等：不存在返回 false 不审计）。 */
    @Transactional
    public boolean removeTag(String assetId, String tag) {
        MarketingAsset a = mustGet(assetId);
        List<String> tags = readTags(a);
        if (!tags.contains(tag)) {
            return false;
        }
        tags.remove(tag);
        a.setTags(writeJson(tags));
        repo.save(a);
        audit("REMOVE_TAG", assetId, Map.of("name", a.getAssetName(), "tag", tag, "tags", tags));
        return true;
    }

    /**
     * 分发到店：追加授权门店（合并去重，已授权不重复）。
     * scope=ALL（全部门店）无需分发，直接返回 false；新增门店数为 0 同样不审计。
     */
    @Transactional
    public boolean distribute(String assetId, List<String> storeCodes) {
        MarketingAsset a = mustGet(assetId);
        if ("ALL".equals(a.getScope())) {
            return false;
        }
        List<String> append = normalizeCodes(storeCodes);
        if (append.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择要分发的门店");
        }
        validateStoreCodes(append);
        Set<String> merged = new LinkedHashSet<>(readCodes(a));
        int before = merged.size();
        merged.addAll(append);
        if (merged.size() == before) {
            return false;
        }
        List<String> mergedList = new ArrayList<>(merged);
        a.setStoreCodes(writeJson(mergedList));
        repo.save(a);
        audit("DISTRIBUTE", assetId, Map.of(
                "name", a.getAssetName(), "addedStores", append,
                "storeCount", mergedList.size(), "storeCodes", mergedList));
        return true;
    }

    // ==================== 内部方法 ====================

    private MarketingAsset mustGet(String assetId) {
        return repo.findById(assetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "素材不存在：" + assetId));
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim().substring(0, 10));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "有效期日期格式不正确（yyyy-MM-dd）");
        }
    }

    private List<String> normalizeTags(List<String> tags) {
        List<String> out = new ArrayList<>();
        if (tags == null) {
            return out;
        }
        for (String t : tags) {
            if (t == null) {
                continue;
            }
            String v = t.trim();
            if (!v.isEmpty() && !out.contains(v)) {
                out.add(v);
            }
        }
        return out;
    }

    private List<String> normalizeCodes(List<String> codes) {
        List<String> out = new ArrayList<>();
        if (codes == null) {
            return out;
        }
        for (String c : codes) {
            if (c == null) {
                continue;
            }
            String v = c.trim();
            if (!v.isEmpty() && !out.contains(v)) {
                out.add(v);
            }
        }
        return out;
    }

    /** 门店编码存在性校验（store-service 不可用时降级放行，不阻断业务）。 */
    private void validateStoreCodes(List<String> codes) {
        if (codes.isEmpty()) {
            return;
        }
        Map<String, String> names = storeNameResolver.resolveNames(codes);
        if (!names.isEmpty()) {
            List<String> unknown = codes.stream().filter(c -> !names.containsKey(c)).toList();
            if (!unknown.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "以下门店不存在或已停用：" + String.join("、", unknown));
            }
        }
    }

    private List<String> readTags(MarketingAsset a) {
        return readList(a.getTags());
    }

    private List<String> readCodes(MarketingAsset a) {
        return readList(a.getStoreCodes());
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(objectMapper.readValue(json, new TypeReference<List<String>>() {}));
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }

    private String writeJson(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list == null ? List.of() : list);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private void audit(String action, String txnNo, Map<String, Object> payload) {
        try {
            audit.record("MARKETING_ASSET", txnNo, DataScope.currentActor(), action,
                    objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            audit.record("MARKETING_ASSET", txnNo, DataScope.currentActor(), action, "{}");
        }
    }

    // ==================== 命令 DTO ====================

    /** 上传素材命令。 */
    public record AssetCmd(String name, String type, List<String> tags, String scope,
                           List<String> storeCodes, String expireAt, String accent, String content) {}

    /** 标签增删命令。 */
    public record TagCmd(String tag) {}

    /** 分发到店命令。 */
    public record DistributeCmd(List<String> storeCodes) {}
}
