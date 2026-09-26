package com.meiyun.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, String>, JpaSpecificationExecutor<Customer> {
    /** 门店全量客户（含已合并）：仅 DSAR 合规场景按门店圈定 customerId 集合用，业务读链路禁用。 */
    List<Customer> findByStoreCode(String storeCode);
    List<Customer> findByLevelAndMergedIntoIsNull(String level);
    List<Customer> findByStatusAndMergedIntoIsNull(String status);
    List<Customer> findByStoreCodeAndMergedIntoIsNull(String storeCode);
    List<Customer> findByLevelAndStoreCodeAndMergedIntoIsNull(String level, String storeCode);

    /** 活跃客户全集（未合并）：自动升降级/等级基线补种/ES 重建与对账/搜索 DB 降级统一走此口径。 */
    List<Customer> findAllByMergedIntoIsNull();

    /** 取号：正式库客户编号 M+3 位序号，查当前最大客户号（防重号，禁内存自增）；库为空返回 null。 */
    @Query("select max(c.customerId) from Customer c where c.customerId like 'M%'")
    String maxMId();

    /** 撞单识别：同门店同手机号视为同一客户（门店内手机号唯一语义）；已合并档案不参与命中。 */
    Optional<Customer> findFirstByStoreCodeAndPhoneAndMergedIntoIsNull(String storeCode, String phone);

    /** 撞单识别：无门店（公海）场景按手机号全局查重；已合并档案不参与命中。 */
    Optional<Customer> findFirstByStoreCodeIsNullAndPhoneAndMergedIntoIsNull(String phone);

    /** 等级人数实时统计：按 customer.level 分组计数（派生统计不入库，不读 member_level.cnt 历史聚合假数据）；已合并档案不计。 */
    @Query("select c.level, count(c) from Customer c where c.mergedInto is null group by c.level")
    List<Object[]> countGroupByLevel();

    /** ES 读时合并：最近建档的 200 个活跃客户参与内存匹配，兜 outbox 中继秒级延迟（建档即可搜到）。 */
    List<Customer> findTop200ByMergedIntoIsNullOrderByCreatedAtDesc();

    /** AI 画像搜索候选：按姓名/手机号/客户编号模糊取前 10，跨服务上下文投影仅需候选集；已合并档案不参与。 */
    @Query("""
            select c from Customer c
            where (c.name like :kw or c.phone like :kw or c.customerId like :kw)
              and c.mergedInto is null
            order by c.createdAt desc
            """)
    List<Customer> searchProfileCandidates(@Param("kw") String keyword, Pageable pageable);

    /** AI 覆盖客户 KPI：客户域活跃客户数（派生统计不入库；已合并档案不计）。 */
    @Query("select count(c) from Customer c where c.mergedInto is null")
    long countAllCustomers();

    /**
     * 同意过期清单（PIPL 第 14 条同意有效期一般 3 年）：consent_at < 指定阈值且未撤回。
     * 供 ComplianceInspectionJob 巡检用，threshold 通常为 now-3y。
     */
    @Query("select c from Customer c where c.consentAt < :threshold and c.consentWithdrawnAt is null and c.mergedInto is null order by c.consentAt asc")
    List<Customer> findConsentExpired(@Param("threshold") OffsetDateTime threshold);

    /**
     * 撞单候选发现（期1 只读）：按归一化手机号分组，取组内有效客户数 &gt; 1 的全部行。
     * 归一化口径与建档查重同：去空白/连字符/括号 → 去 +86/86 国家码前缀；
     * 已匿名化（anonymized_at 非空）客户不参与配对（PII 已销毁，不得重新关联）；
     * 已合并（merged_into 非空）档案不参与配对（数据已并入 master，不再出现于候选）。
     * 只投影候选读模型所需轻量列，组内两两配对/数据域过滤在 Service 层完成。
     */
    @Query(value = """
            select customer_id as customerId, name as name, phone as phone, level as level,
                   store_code as storeCode, owner_staff_id as ownerStaffId, created_at as createdAt
            from customer
            where anonymized_at is null
              and merged_into is null
              and regexp_replace(regexp_replace(regexp_replace(phone,'[\\s\\-()]','','g'),'^\\+?86',''),'^86','')
                  in (
                select norm from (
                    select regexp_replace(regexp_replace(regexp_replace(phone,'[\\s\\-()]','','g'),'^\\+?86',''),'^86','') as norm
                    from customer
                    where anonymized_at is null
                      and merged_into is null
                    group by norm
                    having count(*) > 1
                ) dup)
            order by created_at asc
            """, nativeQuery = true)
    List<DuplicatePhoneRow> findDuplicatePhoneRows();

    /**
     * 生日候选（P5-B90 营销 Flow BIRTHDAY trigger）：出生月/日匹配且活跃（未合并、未匿名化）的客户。
     * 供 marketing-service 经 internal 端点拉取生日关怀候选；已合并/已匿名化档案不参与触达（合规）。
     */
    @Query("select c from Customer c where month(c.birthDate) = :month and day(c.birthDate) = :day and c.mergedInto is null and c.anonymizedAt is null")
    List<Customer> findBirthdayOn(@Param("month") int month, @Param("day") int day);

    /** NPS 回执富化（M3-B1）：按姓名匹配活跃客户带出 customer_id；重名取最近建档；已合并档案不参与命中。 */
    Optional<Customer> findFirstByNameAndMergedIntoIsNullOrderByCreatedAtDesc(String name);
}
