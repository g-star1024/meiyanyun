package com.meiyun.customer;

import com.meiyun.customer.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M3 客户域设置 REST（M3-B1 / DESIGN-M3 §3 M3-18）。读/写分别受 m3settings:view / m3settings:edit 约束。
 * 写操作落 M3_SETTINGS 审计；变更明细由服务层追加 m3_settings_change_log（M3-18 变更记录卡数据源）。
 */
@RestController
@RequestMapping("/api/customer/m3/settings")
public class M3SettingsController {

    private final M3SettingsService service;
    private final AuditRecorder audit;

    public M3SettingsController(M3SettingsService service, AuditRecorder audit) {
        this.service = service;
        this.audit = audit;
    }

    /** 读全量设置（默认值合并）＋变更记录（近 50 条倒序）＋单例行元数据。 */
    @GetMapping
    @RequirePerm("m3settings:view")
    public Map<String, Object> get() {
        M3Settings row = service.currentRowOrNull();
        List<Map<String, Object>> logs = service.listLogs().stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", String.valueOf(l.getId()));
            m.put("action", l.getAction());
            m.put("by", l.getActor());
            m.put("at", l.getCreatedAt());
            return m;
        }).toList();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("settings", service.loadMerged());
        resp.put("logs", logs);
        resp.put("updatedAt", row == null ? null : row.getUpdatedAt());
        resp.put("updatedBy", row == null ? "system" : row.getUpdatedBy());
        return resp;
    }

    /** 保存设置：白名单校验＋差异落变更日志＋桥接 level_rule_config；有差异时落 M3_SETTINGS/SAVE 审计。 */
    @PutMapping
    @RequirePerm("m3settings:edit")
    public Map<String, Object> save(@RequestBody Map<String, Object> req) {
        M3SettingsService.SaveResult result = service.saveSettings(req, DataScope.currentActor());
        if (result.saved()) {
            audit.record("M3_SETTINGS", "1", DataScope.currentActor(), "SAVE",
                    "{\"changedKeys\":" + jsonArray(result.changedKeys()) + "}");
        }
        return respOf(result);
    }

    /** 重置默认（仅复位 12 键，照前端活规格）；有复位时落 M3_SETTINGS/RESET 审计。 */
    @PostMapping("/reset-defaults")
    @RequirePerm("m3settings:edit")
    public Map<String, Object> resetDefaults() {
        M3SettingsService.SaveResult result = service.resetDefaults(DataScope.currentActor());
        if (result.saved()) {
            audit.record("M3_SETTINGS", "1", DataScope.currentActor(), "RESET",
                    "{\"changedKeys\":" + jsonArray(result.changedKeys()) + "}");
        }
        return respOf(result);
    }

    private static Map<String, Object> respOf(M3SettingsService.SaveResult result) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("settings", result.settings());
        resp.put("changedKeys", result.changedKeys());
        resp.put("saved", result.saved());
        return resp;
    }

    private static String jsonArray(List<String> keys) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('\"').append(esc(keys.get(i))).append('\"');
        }
        return sb.append(']').toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
