package com.meiyun.customer;

import com.meiyun.security.DataScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 撞单合并候选发现（期1 只读）：扫描归一化手机号重复的有效客户，组内两两配对，
 * 按数据域 pair 级过滤后输出候选对（掩码手机号 + 门店/归属中文名富化）。
 *
 * <p>安全口径：①仅 customer:merge 角色可达（Controller 注解锁权）；
 * ②候选对两侧均须通过 {@link DataScope#canReadOwned}，任一侧不可读则整对丢弃，
 * 杜绝借配对关系越权探测对侧客户存在性；③公海组（store_code 全空）因
 * canReadStore(null) 对 REGION/STORE/SELF 域一律 false，仅 GROUP（超管）可见。
 *
 * <p>本服务零写入、不留审计（与 list/search 只读语义一致）；合并写逻辑属后续期。
 */
@Service
public class MergeCandidateService {

    /** 手机号归一命中是确定性硬证据，分数取常量，不伪造算法分。 */
    static final double PHONE_MATCH_SCORE = 0.95d;
    /** 分组排序：公海 → 同店 → 跨店（组类型固定展示序）。 */
    private static final Map<String, Integer> GROUP_ORDER = Map.of("POOL", 0, "SAME_STORE", 1, "CROSS_STORE", 2);

    private static final Pattern PHONE_TRIM = Pattern.compile("[\\s\\-()]");
    private static final Pattern CC_86_PREFIX = Pattern.compile("^\\+?86");

    private final CustomerRepository customerRepo;
    private final CustomerMergeRepository mergeRepo;
    private final RefNameResolver nameResolver;

    public MergeCandidateService(CustomerRepository customerRepo, CustomerMergeRepository mergeRepo,
                                 RefNameResolver nameResolver) {
        this.customerRepo = customerRepo;
        this.mergeRepo = mergeRepo;
        this.nameResolver = nameResolver;
    }

    @Transactional(readOnly = true)
    public List<MergeCandidatePairDTO> findCandidates() {
        List<DuplicatePhoneRow> rows = customerRepo.findDuplicatePhoneRows();

        Map<String, List<DuplicatePhoneRow>> groups = new LinkedHashMap<>();
        for (DuplicatePhoneRow row : rows) {
            String norm = normalizePhone(row.getPhone());
            if (norm == null || norm.isBlank()) continue;
            groups.computeIfAbsent(norm, k -> new ArrayList<>()).add(row);
        }

        List<Pair> pairs = new ArrayList<>();
        List<DuplicatePhoneRow> visibleRows = new ArrayList<>();
        for (List<DuplicatePhoneRow> group : groups.values()) {
            if (group.size() < 2) continue;
            String groupType = classifyGroupType(group);
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    DuplicatePhoneRow a = group.get(i);
                    DuplicatePhoneRow b = group.get(j);
                    if (!DataScope.canReadOwned(a.getStoreCode(), a.getOwnerStaffId())
                            || !DataScope.canReadOwned(b.getStoreCode(), b.getOwnerStaffId())) {
                        continue;
                    }
                    // 已标记「非重复档案」的 pair（双向 (A,B)/(B,A) 均排）不再出现于候选
                    if (mergeRepo.existsPairWithStatus(a.getCustomerId(), b.getCustomerId(),
                            CustomerMerge.STATUS_NOT_DUPLICATE)) {
                        continue;
                    }
                    pairs.add(toPair(groupType, a, b));
                    visibleRows.add(a);
                    visibleRows.add(b);
                }
            }
        }
        if (pairs.isEmpty()) return List.of();

        Map<String, String> storeNames = nameResolver.storeNames(
                visibleRows.stream().map(DuplicatePhoneRow::getStoreCode).toList());
        Map<String, String> staffNames = nameResolver.staffNames(
                visibleRows.stream().map(DuplicatePhoneRow::getOwnerStaffId).toList());

        return pairs.stream()
                .map(p -> toDto(p, storeNames, staffNames))
                .sorted(Comparator
                        .comparingInt((MergeCandidatePairDTO d) -> GROUP_ORDER.getOrDefault(d.groupType(), 9))
                        .thenComparing(d -> d.sideA().createdAt())
                        .thenComparing(d -> d.sideB().createdAt())
                        .thenComparing(MergeCandidatePairDTO::pairId))
                .toList();
    }

    /** 归一化手机号：去空白/连字符/括号 → 去 +86/86 国家码前缀（与 SQL/建档查重同口径）。 */
    static String normalizePhone(String phone) {
        if (phone == null) return null;
        String p = PHONE_TRIM.matcher(phone).replaceAll("");
        p = CC_86_PREFIX.matcher(p).replaceFirst("");
        return p;
    }

    /** 组类型：全空=POOL；全非空且相同=SAME_STORE；其余（跨店或公私海混合）=CROSS_STORE。 */
    static String classifyGroupType(List<DuplicatePhoneRow> group) {
        boolean anyNull = group.stream().anyMatch(r -> r.getStoreCode() == null || r.getStoreCode().isBlank());
        if (anyNull) {
            boolean allNull = group.stream().allMatch(r -> r.getStoreCode() == null || r.getStoreCode().isBlank());
            return allNull ? "POOL" : "CROSS_STORE";
        }
        String first = group.get(0).getStoreCode();
        return group.stream().allMatch(r -> Objects.equals(first, r.getStoreCode())) ? "SAME_STORE" : "CROSS_STORE";
    }

    private Pair toPair(String groupType, DuplicatePhoneRow a, DuplicatePhoneRow b) {
        DuplicatePhoneRow sideA;
        DuplicatePhoneRow sideB;
        if (a.getCustomerId().compareTo(b.getCustomerId()) <= 0) {
            sideA = a;
            sideB = b;
        } else {
            sideA = b;
            sideB = a;
        }
        return new Pair("MC-" + sideA.getCustomerId() + "-" + sideB.getCustomerId(), groupType, sideA, sideB);
    }

    private MergeCandidatePairDTO toDto(Pair p, Map<String, String> storeNames, Map<String, String> staffNames) {
        return new MergeCandidatePairDTO(
                p.pairId(),
                p.groupType(),
                List.of("PHONE"),
                PHONE_MATCH_SCORE,
                toSide(p.sideA(), storeNames, staffNames),
                toSide(p.sideB(), storeNames, staffNames));
    }

    private MergeCandidatePairDTO.Side toSide(DuplicatePhoneRow r,
                                              Map<String, String> storeNames, Map<String, String> staffNames) {
        return new MergeCandidatePairDTO.Side(
                r.getCustomerId(),
                r.getName(),
                CustomerService.maskPhone(normalizePhone(r.getPhone()), false),
                r.getLevel(),
                r.getStoreCode(),
                r.getStoreCode() == null ? null : storeNames.get(r.getStoreCode()),
                r.getOwnerStaffId(),
                r.getOwnerStaffId() == null ? null : staffNames.get(r.getOwnerStaffId()),
                r.getCreatedAt());
    }

    /** 组内候选对的中间结构（sideA/sideB 按 customerId 字典序稳定排序）。 */
    private record Pair(String pairId, String groupType, DuplicatePhoneRow sideA, DuplicatePhoneRow sideB) {
    }
}
