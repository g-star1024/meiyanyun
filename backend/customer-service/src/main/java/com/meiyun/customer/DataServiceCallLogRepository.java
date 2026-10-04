package com.meiyun.customer;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DataServiceCallLogRepository extends JpaRepository<DataServiceCallLog, Long> {

    /** 全服务 24h 窗口聚合（一次 group by 取回，避免逐服务 count；返回 service_id, calls, avg_latency, errors）。 */
    @Query(value = "select service_id, count(*), coalesce(avg(latency_ms), 0), "
            + "count(*) filter (where not success) "
            + "from data_service_call_log where called_at > now() - interval '24 hours' "
            + "group by service_id", nativeQuery = true)
    List<Object[]> aggregate24hGroupByService();

    /** 单服务 24h 窗口聚合（无日志时返回空；返回 calls, avg_latency, errors 单行）。 */
    @Query(value = "select service_id, count(*), coalesce(avg(latency_ms), 0), "
            + "count(*) filter (where not success) "
            + "from data_service_call_log where service_id = :serviceId "
            + "and called_at > now() - interval '24 hours' "
            + "group by service_id", nativeQuery = true)
    List<Object[]> aggregate24hByServiceId(Long serviceId);
}
