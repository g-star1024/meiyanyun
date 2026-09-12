package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AutoGrantStateRepository extends JpaRepository<AutoGrantState, Integer> {
}
