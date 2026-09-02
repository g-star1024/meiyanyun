package com.meiyun.customer.dict;

import com.meiyun.customer.CustomerService;
import com.meiyun.customer.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 数据字典管理 API。
 * 业务端（下拉/标签）走「仅启用」端点；管理端走 /manage（含停用项，停用项可见、可重新启用）。
 * 所有变更动作落审计，业务错误抛中文异常由 GlobalExceptionHandler 统一映射，绝不吞异常。
 */
@RestController
@RequestMapping("/api/customer/dictionaries")
@CrossOrigin(origins = "*")
public class DictionaryController {

    @Autowired
    private DictionaryService dictionaryService;

    @Autowired
    private AuditRecorder audit;

    /** 业务端：所有启用字典（按分类分组） */
    @GetMapping
    @RequirePerm({"customer:view", "settings:view"})
    public Map<String, List<Dictionary>> getAll() {
        return dictionaryService.getAllEnabled();
    }

    /** 管理端：全部字典（含停用项），停用项仍展示、可重新启用 */
    @GetMapping("/manage")
    @RequirePerm("settings:view")
    public Map<String, List<Dictionary>> getAllForManage() {
        return dictionaryService.getAllForManage();
    }

    /** 业务端：按分类获取启用字典 */
    @GetMapping("/category/{category}")
    @RequirePerm({"customer:view", "settings:view"})
    public List<Dictionary> getByCategory(@PathVariable String category) {
        return dictionaryService.getByCategory(category);
    }

    /** 管理端：按分类获取全部字典（含停用项） */
    @GetMapping("/manage/category/{category}")
    @RequirePerm("settings:view")
    public List<Dictionary> getByCategoryForManage(@PathVariable String category) {
        return dictionaryService.getByCategoryForManage(category);
    }

    /** 按分类和编码获取启用字典 */
    @GetMapping("/category/{category}/code/{code}")
    @RequirePerm({"customer:view", "settings:view"})
    public List<Dictionary> getByCategoryAndCode(
        @PathVariable String category,
        @PathVariable String code) {
        return dictionaryService.getByCategoryAndCode(category, code);
    }

    /** 创建字典项（含必填/唯一校验；同三元组已停用则复活复用） */
    @PostMapping
    @RequirePerm("settings:edit")
    public Dictionary create(@RequestBody Dictionary dict) {
        Dictionary created = dictionaryService.create(dict);
        audit.record("DICT", String.valueOf(created.getId()), DataScope.currentActor(), "CREATE",
                payload(created));
        return created;
    }

    /** 更新字典项（标签/颜色/图标/排序/启停/描述；业务键不可改） */
    @PutMapping("/{id}")
    @RequirePerm("settings:edit")
    public Dictionary update(@PathVariable Long id, @RequestBody Dictionary dict) {
        Dictionary before = dictionaryService.getById(id);
        boolean wasEnabled = Boolean.TRUE.equals(before.getEnabled());
        Dictionary updated = dictionaryService.update(id, dict);
        boolean nowEnabled = Boolean.TRUE.equals(updated.getEnabled());
        String action = (!wasEnabled && nowEnabled) ? "ENABLE"
                : (wasEnabled && !nowEnabled) ? "DISABLE" : "UPDATE";
        audit.record("DICT", String.valueOf(id), DataScope.currentActor(), action, payload(updated));
        return updated;
    }

    /** 逻辑删除（停用），幂等：已停用不再重复落库/审计 */
    @DeleteMapping("/{id}")
    @RequirePerm("settings:edit")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       @RequestParam(value = "operator", required = false) String operator) {
        Dictionary before = dictionaryService.getById(id);
        boolean changed = dictionaryService.delete(id, null);
        if (changed) {
            audit.record("DICT", String.valueOf(id), DataScope.currentActor(),
                    "DISABLE", payload(before));
        }
        return ResponseEntity.noContent().build();
    }

    /** 所有分类（管理端，含存在停用项的分类） */
    @GetMapping("/categories")
    @RequirePerm("settings:view")
    public List<String> getCategories() {
        return dictionaryService.getCategories();
    }

    /** 按分类统计启用数量 */
    @GetMapping("/statistics")
    @RequirePerm("settings:view")
    public Map<String, Long> getStatistics() {
        return dictionaryService.countByCategory();
    }

    /** 审计 payload 必须是合法 JSON（audit_log.payload 为 jsonb），统一在此拼装 */
    private String payload(Dictionary d) {
        return "{\"category\":\"" + esc(d.getCategory()) + "\",\"code\":\"" + esc(d.getDictCode())
                + "\",\"value\":\"" + esc(d.getDictValue()) + "\",\"label\":\"" + esc(d.getDictLabel())
                + "\",\"enabled\":" + Boolean.TRUE.equals(d.getEnabled()) + "}";
    }

    private String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
