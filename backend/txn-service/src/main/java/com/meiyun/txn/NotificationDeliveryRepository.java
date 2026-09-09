package com.meiyun.txn;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {

    List<NotificationDelivery> findByNotificationId(Long notificationId);

    Optional<NotificationDelivery> findByNotificationIdAndChannel(Long notificationId, String channel);

    /** 尚未扇出（无 delivery 记录）的通知，按 id 升序截取一批。 */
    @Query("select n from Notification n where n.id not in " +
            "(select d.notificationId from NotificationDelivery d) order by n.id asc")
    List<Notification> findUnfanned(Pageable pageable);

    /**
     * 可取的待重试投递：
     * <ul>
     *   <li>PENDING：崩溃残行（先落 PENDING 再发送，中途宕机）——next_attempt_at 恒为 null，立即捞取自愈；</li>
     *   <li>FAILED/DEFERRED：退避窗口已到（next_attempt_at 为 null 视为已到期）。</li>
     * </ul>
     * SENT/SKIPPED/DEAD 为终态，永不重试。
     */
    @Query("select d from NotificationDelivery d where d.status in ('PENDING','FAILED','DEFERRED') " +
            "and (d.nextAttemptAt is null or d.nextAttemptAt <= :now) order by d.id asc")
    List<NotificationDelivery> findRetryable(@Param("now") OffsetDateTime now, Pageable pageable);

    long countByStatus(String status);
}
