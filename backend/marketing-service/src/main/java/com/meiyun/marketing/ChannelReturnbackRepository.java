package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChannelReturnbackRepository extends JpaRepository<ChannelReturnback, Long> {

    List<ChannelReturnback> findByChannelCodeOrderByReceivedAtDesc(String channelCode);

    List<ChannelReturnback> findByStatusOrderByReceivedAtDesc(String status);

    /** 渠道内业务号幂等查重（bizRef 非空回调防重放；PG 中 biz_ref 为 NULL 的行不受唯一约束影响）。 */
    Optional<ChannelReturnback> findByChannelCodeAndBizRef(String channelCode, String bizRef);
}
