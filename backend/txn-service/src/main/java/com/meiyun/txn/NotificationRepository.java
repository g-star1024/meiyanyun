package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 某人的通知（最新在前）。 */
    List<Notification> findByRecipientOrderByIdDesc(String recipient);

    long countByRecipientAndReadFalse(String recipient);

    boolean existsByIdemKey(String idemKey);

    /** 某人全部未读置已读（通知中心「全部已读」）。 */
    @Modifying
    @Transactional
    @Query("update Notification n set n.read = true where n.recipient = :recipient and n.read = false")
    int markAllRead(@Param("recipient") String recipient);
}
