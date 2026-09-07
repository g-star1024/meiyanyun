package com.meiyun.store.room;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TreatmentBedRepository extends JpaRepository<TreatmentBed, Long> {

    List<TreatmentBed> findByStoreCodeOrderByBedCode(String storeCode);

    List<TreatmentBed> findByRoomIdOrderByBedCode(Long roomId);

    Optional<TreatmentBed> findByStoreCodeAndBedCode(String storeCode, String bedCode);
}
