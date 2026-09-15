package com.meiyun.txn;

import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 调度中心（P5-B49 卡12，M1 集团管控 / 调度中心）：资源时间轴、待派单、派单、释放。
 *
 * <p>类级 {@code dispatch:view} 放行只读；派单/释放两个写动作在方法级收紧为 {@code dispatch:edit}
 * （方法注解读取优先于类注解）。门店为必传参数（单门店视角），服务层再做数据域断言；
 * 所有业务拒绝（404/400/409/422）一律中文，由全局异常处理映射，不裸 500。
 */
@RestController
@RequestMapping("/api/txn/dispatch")
@RequirePerm("dispatch:view")
public class DispatchController {

    private final DispatchService service;

    public DispatchController(DispatchService service) {
        this.service = service;
    }

    /** 资源（医生/治疗室/设备）及当日占用块（活跃 + DONE 完成回显）；type 可选 DOCTOR/ROOM/DEVICE，date 默认今日。 */
    @GetMapping("/resources")
    public List<DispatchService.ResourceView> resources(@RequestParam String storeCode,
                                                        @RequestParam(required = false) String type,
                                                        @RequestParam(required = false) LocalDate date) {
        return service.resources(storeCode, type, date);
    }

    /** 待派单工单（当日已预约/已到店且无派单占用含 DONE 终态，已到店排前 + 时间升序）。 */
    @GetMapping("/jobs")
    public List<DispatchService.JobView> jobs(@RequestParam String storeCode,
                                              @RequestParam(required = false) LocalDate date) {
        return service.jobs(storeCode, date);
    }

    /** 派单：body {apptNo, resourceType, resourceId}，start 锚定预约时段，时长固定 60 分钟。 */
    @PostMapping("/dispatch")
    @RequirePerm("dispatch:edit")
    public DispatchService.AssignmentView dispatch(@RequestParam String storeCode,
                                                   @RequestBody DispatchService.DispatchCmd cmd) {
        return service.dispatch(storeCode, cmd);
    }

    /** 释放派单（保留行置 RELEASED，时段回收；重复释放 422）。 */
    @PostMapping("/assignments/{id}/release")
    @RequirePerm("dispatch:edit")
    public DispatchService.AssignmentView release(@PathVariable Long id,
                                                  @RequestParam String storeCode) {
        return service.release(id, storeCode);
    }
}
