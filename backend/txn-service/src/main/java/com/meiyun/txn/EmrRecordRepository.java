package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 病历仓储（emr_record）。
 */
public interface EmrRecordRepository extends JpaRepository<EmrRecord, String>, JpaSpecificationExecutor<EmrRecord> {

    /** 方案单联动幂等：同一方案单同一病历类型仅一条（签首程/治疗完成防双写）。 */
    Optional<EmrRecord> findFirstByConsultIdAndType(String consultId, String type);

    List<EmrRecord> findByCustomerIdOrderByVisitDateDesc(String customerId);

    /**
     * 生成当日不重号病历号：EM + yyyyMMdd + - + 6 位序号（序号从第 12 位起）。
     * 基础件号 char_length=17（EM2 位+8 位日期+1 连字符+6 位序号），据此排除
     * 「源号-R{n}」形式（长度 20+）的修订号，使修订号不进查库池。
     */
    @Query(value = "select coalesce(max(cast(substring(emr_no from 12) as bigint)),0) "
            + "from emr_record where emr_no like :prefix and char_length(emr_no) = 17",
            nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
