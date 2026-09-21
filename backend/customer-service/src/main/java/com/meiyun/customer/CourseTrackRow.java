package com.meiyun.customer;

import java.time.Instant;

/**
 * B83 卡1 L66 疗程跟踪聚合读 native query 投影行（member_card JOIN customer）。
 *
 * <p>只投影读模型所需列：customer 名/手机号从 customer 表 JOIN 取（避免 N+1），
 * 卡项名/总次/余次/余额/状态/到期时间从 member_card 取。
 * trackStatus/daysLeft/usedTimes/phoneMask 在 Controller 侧 Java 推导。
 */
public interface CourseTrackRow {

    String getCardNo();

    String getCustomerId();

    String getCustomerName();

    String getPhone();

    String getCardItem();

    Integer getTotalTimes();

    Integer getRemainTimes();

    Long getBalance();

    String getStatus();

    Instant getExpiresAt();
}
