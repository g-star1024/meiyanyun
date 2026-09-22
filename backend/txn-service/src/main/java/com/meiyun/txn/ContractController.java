package com.meiyun.txn;

import com.meiyun.security.RequirePerm;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * P5-B85 卡3 合同管理（HTTP 薄边界，业务全在 {@link ContractService}）：
 * 新建草稿 / 列表（customerId、status 过滤，门店数据域收敛）/ 详情 / 状态流转（生效|履行|终止）。
 * 权限：查看 contract:view，编辑 contract:edit。
 */
@RestController
@RequestMapping("/api/txn/contracts")
public class ContractController {

    private final ContractService service;

    public ContractController(ContractService service) {
        this.service = service;
    }

    @PostMapping
    @RequirePerm("contract:edit")
    public Contract create(@RequestBody @Valid ContractService.ContractCmd cmd) {
        return service.create(cmd);
    }

    @GetMapping
    @RequirePerm("contract:view")
    public List<Contract> list(@RequestParam(required = false) String customerId,
                               @RequestParam(required = false) String status) {
        return service.list(customerId, status);
    }

    @GetMapping("/{no}")
    @RequirePerm("contract:view")
    public Contract get(@PathVariable String no) {
        return service.require(no);
    }

    @PostMapping("/{no}/activate")
    @RequirePerm("contract:edit")
    public Contract activate(@PathVariable String no) {
        return service.activate(no);
    }

    @PostMapping("/{no}/complete")
    @RequirePerm("contract:edit")
    public Contract complete(@PathVariable String no) {
        return service.complete(no);
    }

    @PostMapping("/{no}/terminate")
    @RequirePerm("contract:edit")
    public Contract terminate(@PathVariable String no,
                              @RequestBody(required = false) ContractService.TerminateCmd cmd) {
        return service.terminate(no, cmd == null ? null : cmd.terminateReason());
    }
}
