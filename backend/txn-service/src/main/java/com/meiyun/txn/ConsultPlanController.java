package com.meiyun.txn;

import com.meiyun.security.RequirePerm;
import com.meiyun.txn.ConsultPlanService.DoctorEditCmd;
import com.meiyun.txn.ConsultPlanService.PlanView;
import com.meiyun.txn.ConsultPlanService.RetailCmd;
import com.meiyun.txn.ConsultPlanService.ReviewCmd;
import com.meiyun.txn.ConsultPlanService.SignEmrCmd;
import com.meiyun.txn.ConsultPlanService.SubmitCmd;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 咨询方案单控制器（M4-08 咨询面诊 → M4-09b 医生二次审核 → 签病历生成缴费单）。
 *
 * <p>诊疗主线：submit（待审核）→ approve/reject/doctor-edit → sign-emr（自动开「待收款」缴费单）→ 收银台收款。
 * 零售支线：retail-order（/prescription 现场直开「待收款」，不走医生审核）。
 *
 * <p>本类仅做 HTTP 薄封装，业务规则与状态机全部在 {@link ConsultPlanService}；
 * 写动作四件套（参数校验 / 幂等 / 全动作审计 / 中文错误）在 Service 内落实。
 */
@RestController
@RequestMapping("/api/txn")
public class ConsultPlanController {

    private final ConsultPlanService planService;

    public ConsultPlanController(ConsultPlanService planService) {
        this.planService = planService;
    }

    // ==================== 咨询师：提交方案 → 待医生审核 ====================

    @PostMapping("/consult-plan")
    @RequirePerm("consult:create")
    public PlanView submit(@RequestBody SubmitCmd cmd) {
        return planService.submit(cmd);
    }

    // ==================== 医生：二次审核 ====================

    /** 审核通过（→ 待写病历）。 */
    @PostMapping("/consult-plan/{planId}/approve")
    @RequirePerm("consult:review")
    public PlanView approve(@PathVariable String planId, @RequestBody ReviewCmd cmd) {
        return planService.approve(planId, cmd);
    }

    /** 审核驳回（须填原因，咨询师据此改单重提）。 */
    @PostMapping("/consult-plan/{planId}/reject")
    @RequirePerm("consult:review")
    public PlanView reject(@PathVariable String planId, @RequestBody ReviewCmd cmd) {
        return planService.reject(planId, cmd);
    }

    /** 医生改单并通过（改单说明留痕，咨询师可见）。 */
    @PostMapping("/consult-plan/{planId}/doctor-edit")
    @RequirePerm("consult:review")
    public PlanView doctorEdit(@PathVariable String planId, @RequestBody DoctorEditCmd cmd) {
        return planService.doctorEdit(planId, cmd);
    }

    /**
     * 签首程病历 → 系统自动生成「待收款」缴费单，返回订单读模型。
     * body 可空（诊断取咨询结论）；幂等：已生成缴费单则直接返回原单，不重复开单。
     */
    @PostMapping("/consult-plan/{planId}/sign-emr")
    @RequirePerm("emr:create")
    public M4FlowController.OrderView signEmr(@PathVariable String planId,
                                              @RequestBody(required = false) SignEmrCmd cmd) {
        return planService.signEmr(planId, cmd);
    }

    // ==================== 读模型 ====================

    /** 方案单队列（咨询师 / 医生工作台）：按状态、门店过滤，分页。 */
    @GetMapping("/consult-plan")
    @RequirePerm("consult:view")
    public Page<PlanView> queue(@PageableDefault(size = 20) Pageable pageable,
                                @RequestParam(required = false) String status,
                                @RequestParam(required = false) String storeCode) {
        return planService.queue(status, storeCode, pageable);
    }

    /** 方案单详情（含子项、留痕、关联缴费单状态）。 */
    @GetMapping("/consult-plan/{planId}")
    @RequirePerm("consult:view")
    public PlanView get(@PathVariable String planId) {
        return planService.get(planId);
    }

    // ==================== 零售支线：现场直开缴费单（/prescription） ====================

    /** 零售 / 药妆 / 产品现场开单，不走医生审核，直接生成「待收款」订单。散客也须是建档客户。 */
    @PostMapping("/retail-order")
    @RequirePerm("prescription:create")
    public M4FlowController.OrderView retail(@RequestBody RetailCmd cmd) {
        return planService.createRetailOrder(cmd);
    }
}
