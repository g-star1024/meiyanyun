package com.meiyun.customer;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 客诉单 Repository（M3-B8）。 */
public interface ComplaintRepository extends JpaRepository<Complaint, Long> {

    /** 列表：登记时刻倒序（与前端 mock unshift 头部插入口径一致）。 */
    List<Complaint> findAllByOrderByCreatedAtDesc();

    /** 当日单号取号：查 DB 当日最大号（禁内存序列，照 RiskRecordRepository 同构）。 */
    Optional<Complaint> findTopByComplaintNoLikeOrderByComplaintNoDesc(String prefix);
}
