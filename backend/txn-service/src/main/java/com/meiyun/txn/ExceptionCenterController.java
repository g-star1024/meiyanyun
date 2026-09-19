package com.meiyun.txn;

import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 统一异常中心（B63 卡2 L83）：门店运营视角的跨源异常单据<b>只读归集</b>入口。
 *
 * <p>收口 BOM 扣料异常（bom_deduct_exception）、核销异常（writeoff_record ABNORMAL）、
 * 财务异常账单（fin_abnormal_bill）三源，统一列表/详情/门店 DataScope/类型/级别/状态；
 * 权限 {@code exception:view}（区域经理、店长）。中心<b>零写操作</b>——处置按各单据 disposeRoute
 * 跳既有处置视图，不新建处置链、不重复入账、不绕过审批。
 */
@RestController
@RequestMapping("/api/txn/exceptions")
public class ExceptionCenterController {

    private final ExceptionCenterService service;

    public ExceptionCenterController(ExceptionCenterService service) {
        this.service = service;
    }

    /** 统一异常列表：source/status/storeCode 均可选；按发生时间倒序。 */
    @GetMapping
    @RequirePerm("exception:view")
    public List<ExceptionCenterItem> list(@RequestParam(required = false) String source,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String storeCode) {
        return service.list(source, status, storeCode);
    }

    /** 统一异常详情：id 带源前缀（BOM:BEX... / WO:WO... / FIN:AB...），不存在或越权统一 404。 */
    @GetMapping("/{id}")
    @RequirePerm("exception:view")
    public ExceptionCenterItem detail(@PathVariable String id) {
        return service.detail(id);
    }
}
