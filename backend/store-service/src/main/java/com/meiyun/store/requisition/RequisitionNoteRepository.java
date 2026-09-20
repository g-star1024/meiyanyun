package com.meiyun.store.requisition;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RequisitionNoteRepository extends JpaRepository<RequisitionNote, Long> {

    List<RequisitionNote> findByRqIdOrderByNoteAtDesc(Long rqId);
}
