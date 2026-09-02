package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface AppointmentRepository extends JpaRepository<Appointment, String>, JpaSpecificationExecutor<Appointment> {

    List<Appointment> findByStoreCodeAndApptDateOrderByApptTimeAsc(String storeCode, LocalDate apptDate);

    List<Appointment> findByApptDateOrderByStoreCodeAscApptTimeAsc(LocalDate apptDate);

    long countByStatus(String status);

    /** 看板统计：指定日期（不传则全部）各状态数量。PG 需 CAST 定型参数，否则 ? is null 报 42P18。 */
    @Query(value = "select status, count(*) from appointment " +
           "where (cast(:date as date) is null or appt_date = :date) " +
           "and (cast(:store as varchar) is null or store_code = :store) group by status",
           nativeQuery = true)
    List<Object[]> boardStats(@Param("date") LocalDate date, @Param("store") String store);

    /** 反洗客比对：同一客户近 N 天内在不同门店的预约/到店记录。 */
    @Query("select a from Appointment a where a.customerId = :cid and a.apptDate >= :since order by a.apptDate desc")
    List<Appointment> recentByCustomer(@Param("cid") String customerId, @Param("since") LocalDate since);

    /** 幂等防重：同一客户同一天同一时段是否已有未取消预约（防双击/重复提交）。 */
    boolean existsByCustomerIdAndApptDateAndApptTimeAndStatusNot(
            String customerId, LocalDate apptDate, String apptTime, String status);

    /** 当日预约号最大序号（appt_no 形如 AP20260901-000007，序号从第 12 位起 6 位），用于生成不重号的下一个号。 */
    @Query(value = "select coalesce(max(cast(substring(appt_no from 12) as bigint)), 0) " +
           "from appointment where appt_no like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
