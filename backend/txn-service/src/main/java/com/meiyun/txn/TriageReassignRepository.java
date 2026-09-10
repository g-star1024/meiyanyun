package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TriageReassignRepository extends JpaRepository<TriageReassign, Long> {

    /** 队列读模型批量富化：一次拉全本批登记的全部改派历史，id 正序即改派发生次序。 */
    List<TriageReassign> findByArrivalIdInOrderByIdAsc(List<String> arrivalIds);
}
