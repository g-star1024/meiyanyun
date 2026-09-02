package com.meiyun.customer.dict;

import com.meiyun.customer.CustomerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class DictionaryService {

    @Autowired
    private DictionaryRepository dictionaryRepository;

    /**
     * 获取所有启用的字典（按分类分组）——供业务端下拉/标签使用
     */
    @Cacheable(value = "dictionary", key = "'all'")
    public Map<String, List<Dictionary>> getAllEnabled() {
        List<Dictionary> all = dictionaryRepository.findByEnabledTrueOrderByCategoryAscSortOrderAsc();
        return all.stream().collect(Collectors.groupingBy(Dictionary::getCategory));
    }

    /**
     * 【管理端】查询全部字典（含停用项），按分类分组。停用项必须可见，否则无法重新启用。
     */
    public Map<String, List<Dictionary>> getAllForManage() {
        return dictionaryRepository.findAllByOrderByCategoryAscSortOrderAscIdAsc()
                .stream().collect(Collectors.groupingBy(Dictionary::getCategory));
    }

    /**
     * 【管理端】按分类查询全部字典（含停用项）
     */
    public List<Dictionary> getByCategoryForManage(String category) {
        return dictionaryRepository.findByCategoryOrderBySortOrderAscIdAsc(category);
    }

    /**
     * 按分类获取启用字典
     */
    @Cacheable(value = "dictionary", key = "#category")
    public List<Dictionary> getByCategory(String category) {
        return dictionaryRepository.findByCategoryAndEnabledTrueOrderBySortOrderAsc(category);
    }

    /**
     * 按分类和编码获取启用字典
     */
    public List<Dictionary> getByCategoryAndCode(String category, String dictCode) {
        return dictionaryRepository.findByCategoryAndDictCodeAndEnabledTrueOrderBySortOrderAsc(category, dictCode);
    }

    /**
     * 创建字典项。
     * 四件套：① 必填校验 ② 幂等（同 分类+编码+值 已启用→冲突中文报错；已停用→复活复用，不新增重复行）
     */
    @CacheEvict(value = "dictionary", allEntries = true)
    @Transactional
    public Dictionary create(Dictionary dict) {
        String category = trim(dict.getCategory());
        String code = trim(dict.getDictCode());
        String value = trim(dict.getDictValue());
        String label = trim(dict.getDictLabel());
        if (category.isEmpty() || code.isEmpty() || value.isEmpty() || label.isEmpty()) {
            throw new IllegalArgumentException("分类、字典编码、字典值、显示标签均为必填项");
        }
        if (category.length() > 50 || code.length() > 50 || value.length() > 50) {
            throw new IllegalArgumentException("分类/编码/字典值长度不能超过 50 个字符");
        }
        if (label.length() > 100) {
            throw new IllegalArgumentException("显示标签长度不能超过 100 个字符");
        }

        Optional<Dictionary> existing = dictionaryRepository
                .findByCategoryAndDictCodeAndDictValue(category, code, value);
        if (existing.isPresent()) {
            Dictionary d = existing.get();
            if (Boolean.TRUE.equals(d.getEnabled())) {
                throw new IllegalArgumentException(
                        "字典项已存在：" + category + " / " + code + " / " + value + "（" + d.getDictLabel() + "）");
            }
            // 同三元组曾被停用 → 复活复用，避免撞唯一约束，也让「误删后重建」幂等
            d.setEnabled(true);
            d.setDictLabel(label);
            d.setDictColor(dict.getDictColor());
            d.setDictIcon(dict.getDictIcon());
            d.setSortOrder(dict.getSortOrder() == null ? 0 : dict.getSortOrder());
            d.setDescription(dict.getDescription());
            d.setUpdatedBy(dict.getUpdatedBy());
            return dictionaryRepository.save(d);
        }

        dict.setCategory(category);
        dict.setDictCode(code);
        dict.setDictValue(value);
        dict.setDictLabel(label);
        if (dict.getEnabled() == null) dict.setEnabled(true);
        if (dict.getSortOrder() == null) dict.setSortOrder(0);
        return dictionaryRepository.save(dict);
    }

    /**
     * 按 id 查询（不存在抛中文 NotFound，供审计/更新前取快照）
     */
    public Dictionary getById(Long id) {
        return dictionaryRepository.findById(id)
                .orElseThrow(() -> new CustomerService.NotFound("字典项不存在（id=" + id + "）"));
    }

    /**
     * 更新字典项（仅允许改展示属性：标签/颜色/图标/排序/启停/描述；分类+编码+值为业务键，不可改）。
     */
    @CacheEvict(value = "dictionary", allEntries = true)
    @Transactional
    public Dictionary update(Long id, Dictionary dict) {
        Dictionary existing = dictionaryRepository.findById(id)
                .orElseThrow(() -> new CustomerService.NotFound("字典项不存在（id=" + id + "）"));

        if (dict.getDictLabel() != null) {
            String label = trim(dict.getDictLabel());
            if (label.isEmpty()) throw new IllegalArgumentException("显示标签不能为空");
            existing.setDictLabel(label);
        }
        existing.setDictColor(dict.getDictColor());
        existing.setDictIcon(dict.getDictIcon());
        if (dict.getSortOrder() != null) existing.setSortOrder(dict.getSortOrder());
        if (dict.getEnabled() != null) existing.setEnabled(dict.getEnabled());
        existing.setDescription(dict.getDescription());
        existing.setUpdatedBy(dict.getUpdatedBy());

        return dictionaryRepository.save(existing);
    }

    /**
     * 逻辑删除（停用）。幂等：已是停用态不重复落库。返回 true 表示本次实际发生停用（供调用方决定是否记审计）。
     */
    @CacheEvict(value = "dictionary", allEntries = true)
    @Transactional
    public boolean delete(Long id, Long operator) {
        Dictionary dict = dictionaryRepository.findById(id)
                .orElseThrow(() -> new CustomerService.NotFound("字典项不存在（id=" + id + "）"));
        if (!Boolean.TRUE.equals(dict.getEnabled())) {
            return false; // 幂等：已停用，不重复落库/审计
        }
        dict.setEnabled(false);
        dict.setUpdatedBy(operator);
        dictionaryRepository.save(dict);
        return true;
    }

    /**
     * 获取所有分类（管理端：含存在停用项的分类）
     */
    public List<String> getCategories() {
        return dictionaryRepository.findAllDistinctCategories();
    }

    /**
     * 按分类统计启用数量
     */
    public Map<String, Long> countByCategory() {
        return dictionaryRepository.countByCategory().stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> (Long) row[1]
                ));
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
