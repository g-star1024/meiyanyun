package com.meiyun.store.procurement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** 采购订单仓库 */
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    Optional<PurchaseOrder> findByPoNo(String poNo);

    // cast(... as string) 原因同 ConsumableRepository：stringtype=unspecified 下
    // null 命名参数无类型上下文，PG 无法推断「? is null」/ lower(?) 的参数类型。
    @Query("select p from PurchaseOrder p where (cast(:storeCode as string) is null "
            + "or p.storeCode = :storeCode) "
            + "and (cast(:status as string) is null or p.status = :status) "
            + "order by p.id desc")
    List<PurchaseOrder> search(@Param("storeCode") String storeCode,
                               @Param("status") String status);

    /**
     * 当日采购单最大序号（po_no 形如 PO20260914-000001）。
     *
     * <p>PO 为 <b>2 字母</b>前缀，连字符落在第 11 位，序号须从第 <b>12</b> 位起 6 位
     * （BOM 为 3 字母前缀从第 13 位起，勿照抄错位，否则 cast 成负数导致序号回退撞键）。
     */
    @Query(value = "select coalesce(max(cast(substring(po_no from 12) as bigint)), 0) "
            + "from purchase_order where po_no like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
