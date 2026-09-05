package com.meiyun.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface FundEntryRepository extends JpaRepository<FundEntry, Long> {

    Optional<FundEntry> findByIdemKey(String idemKey);

    List<FundEntry> findByOccurredAtBetweenOrderByOccurredAtAsc(OffsetDateTime from, OffsetDateTime to);
}
