package com.meiyun.c.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CMemberAuthRepository extends JpaRepository<CMemberAuth, Long> {

    Optional<CMemberAuth> findByOpenid(String openid);

    Optional<CMemberAuth> findByPhone(String phone);
}
