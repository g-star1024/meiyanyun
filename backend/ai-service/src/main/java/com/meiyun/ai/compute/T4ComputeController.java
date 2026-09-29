package com.meiyun.ai.compute;

import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T4 算力管理业务出口（/ai/compute）。
 * 类级权限 compute:view；写端点方法级细码（拦截语义为方法级覆盖类级）：
 * quotas=compute:alloc / gpus/{code}/status=compute:edit（权限矩阵零新码，契约④）。
 */
@RestController
@RequestMapping("/api/ai/t4/compute")
@RequirePerm("compute:view")
public class T4ComputeController {

    private final T4ComputeService t4ComputeService;

    public T4ComputeController(T4ComputeService t4ComputeService) {
        this.t4ComputeService = t4ComputeService;
    }

    @GetMapping
    public T4ComputeService.ComputeView overview() {
        return t4ComputeService.overview();
    }

    @PostMapping("/quotas")
    @RequirePerm("compute:alloc")
    public T4ComputeService.QuotaView allocate(@RequestBody T4ComputeService.AllocateCmd cmd) {
        return t4ComputeService.allocate(cmd, DataScope.currentActor());
    }

    @PostMapping("/gpus/{code}/status")
    @RequirePerm("compute:edit")
    public T4ComputeService.GpuView updateGpuStatus(@PathVariable String code,
                                                    @RequestBody T4ComputeService.UpdateStatusCmd cmd) {
        return t4ComputeService.updateGpuStatus(code, cmd, DataScope.currentActor());
    }
}
