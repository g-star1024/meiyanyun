package com.meiyun.customer;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 客诉时间线 Repository（M3-B8）。 */
public interface ComplaintLogRepository extends JpaRepository<ComplaintLog, Long> {

    /** 单条客诉的时间线：按时刻,id 升序（种子多条同刻，id 次级排序保落库序）。 */
    List<ComplaintLog> findByComplaintIdOrderByAtTimeAscIdAsc(Long complaintId);

    /** 列表批量组装时间线（规避 N+1）：按时刻,id 升序，Service 层按 complaintId 分组。 */
    List<ComplaintLog> findByComplaintIdInOrderByAtTimeAscIdAsc(Collection<Long> ids);
}
