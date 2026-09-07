package com.meiyun.store.room;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RoomOperationLogRepository extends JpaRepository<RoomOperationLog, Long> {

    /** 房间/床位操作日志（按时间倒序，Pageable 限条数）；storeCode 为空取全量（数据域由调用方过滤）。 */
    @Query("select l from RoomOperationLog l where "
            + "(cast(:storeCode as string) is null or l.storeCode = cast(:storeCode as string)) "
            + "order by l.createdAt desc, l.id desc")
    List<RoomOperationLog> search(@Param("storeCode") String storeCode, Pageable pageable);
}
