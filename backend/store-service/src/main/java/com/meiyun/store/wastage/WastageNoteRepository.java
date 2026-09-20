package com.meiyun.store.wastage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WastageNoteRepository extends JpaRepository<WastageNote, Long> {

    List<WastageNote> findByWsIdOrderByNoteAtDesc(Long wsId);
}
