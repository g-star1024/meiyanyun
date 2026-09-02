package com.meiyun.store;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RegionDistRepository extends JpaRepository<RegionDist, String> {
}
