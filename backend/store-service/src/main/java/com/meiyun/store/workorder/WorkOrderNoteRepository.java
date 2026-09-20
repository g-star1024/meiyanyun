package com.meiyun.store.workorder;

import java.util.List;

public interface WorkOrderNoteRepository extends org.springframework.data.jpa.repository.JpaRepository<WorkOrderNote, Long> {

    List<WorkOrderNote> findByWoIdOrderByNoteAtDesc(Long woId);
}
