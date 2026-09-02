package com.meiyun.customer.dict;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DictionaryRepository extends JpaRepository<Dictionary, Long> {
    
    /**
     * 按分类查询字典列表
     */
    List<Dictionary> findByCategoryAndEnabledTrueOrderBySortOrderAsc(String category);
    
    /**
     * 按分类和编码查询
     */
    List<Dictionary> findByCategoryAndDictCodeAndEnabledTrueOrderBySortOrderAsc(
        String category, String dictCode);
    
    /**
     * 查询所有启用的字典
     */
    List<Dictionary> findByEnabledTrueOrderByCategoryAscSortOrderAsc();
    
    /**
     * 按唯一约束查询（含已停用项，用于「同三元组停用后重新启用」判断）
     */
    Optional<Dictionary> findByCategoryAndDictCodeAndDictValue(
        String category, String dictCode, String dictValue);

    /**
     * 【管理端】按分类查询全部字典（含停用项），停用项仍可见、可重新启用
     */
    List<Dictionary> findByCategoryOrderBySortOrderAscIdAsc(String category);

    /**
     * 【管理端】查询全部字典（含停用项）
     */
    List<Dictionary> findAllByOrderByCategoryAscSortOrderAscIdAsc();

    /**
     * 【管理端】查询所有分类（去重，含存在停用项的分类）
     */
    @Query("SELECT DISTINCT d.category FROM Dictionary d ORDER BY d.category")
    List<String> findAllDistinctCategories();
    
    /**
     * 查询所有分类（去重）
     */
    @Query("SELECT DISTINCT d.category FROM Dictionary d WHERE d.enabled = true ORDER BY d.category")
    List<String> findDistinctCategories();
    
    /**
     * 按分类统计数量
     */
    @Query("SELECT d.category, COUNT(d) FROM Dictionary d WHERE d.enabled = true GROUP BY d.category")
    List<Object[]> countByCategory();
}
