package com.meiyun.store.procurement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 采购订单仓库。
 *
 * <p>列表查询走 {@link JpaSpecificationExecutor}，由 Service 以 {@code DataScope.storeSpec}
 * 强制叠加当前登录人数据域（B50 卡2：原 {@code cast(:storeCode as string) is null} 写法在
 * REGION 多店账号无参时退化为全量，存在跨区越权）。
 */
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long>,
        JpaSpecificationExecutor<PurchaseOrder> {

    Optional<PurchaseOrder> findByPoNo(String poNo);

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
