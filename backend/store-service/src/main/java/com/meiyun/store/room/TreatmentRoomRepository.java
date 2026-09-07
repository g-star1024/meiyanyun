package com.meiyun.store.room;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TreatmentRoomRepository extends JpaRepository<TreatmentRoom, Long> {

    Optional<TreatmentRoom> findByStoreCodeAndRoomCode(String storeCode, String roomCode);

    /**
     * 房间检索：storeCode/type/status 均为可选过滤（null 不过滤）。
     * stringtype=unspecified 连接串下 null 参数须 cast(:x as string)，否则 PG 推断为 bytea 报错。
     */
    @Query("select r from TreatmentRoom r where "
            + "(cast(:storeCode as string) is null or r.storeCode = cast(:storeCode as string)) "
            + "and (cast(:type as string) is null or r.roomType = cast(:type as string)) "
            + "and (cast(:status as string) is null or r.status = cast(:status as string)) "
            + "order by r.roomCode")
    List<TreatmentRoom> search(@Param("storeCode") String storeCode,
                               @Param("type") String type,
                               @Param("status") String status);
}
