package com.meiyun.customer.dict;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 数据字典实体 - 支持动态增删改
 */
@Entity
@Table(name = "sys_dictionary", indexes = {
    @Index(name = "idx_dict_category", columnList = "category"),
    @Index(name = "idx_dict_enabled", columnList = "enabled")
})
public class Dictionary {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, length = 50)
    private String category; // 字典分类：CUSTOMER/APPOINTMENT/ORDER等
    
    @Column(name = "dict_code", nullable = false, length = 50)
    private String dictCode; // 字典编码：SOURCE/STATUS/LEVEL等
    
    @Column(name = "dict_value", nullable = false, length = 50)
    private String dictValue; // 字典值
    
    @Column(name = "dict_label", nullable = false, length = 100)
    private String dictLabel; // 显示标签
    
    @Column(name = "dict_color", length = 20)
    private String dictColor; // 颜色标识
    
    @Column(name = "dict_icon", length = 50)
    private String dictIcon; // 图标
    
    @Column(name = "sort_order")
    private Integer sortOrder = 0;
    
    @Column(name = "is_enabled", nullable = false)
    private Boolean enabled = true;
    
    @Column(length = 255)
    private String description;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    
    @Column(name = "created_by")
    private Long createdBy;
    
    @Column(name = "updated_by")
    private Long updatedBy;

    /** 操作人（仅用于审计透传，不落库；管理端暂无登录用户体系时由前端传 admin/操作人姓名） */
    @Transient
    private String operator;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    
    public String getDictCode() { return dictCode; }
    public void setDictCode(String dictCode) { this.dictCode = dictCode; }
    
    public String getDictValue() { return dictValue; }
    public void setDictValue(String dictValue) { this.dictValue = dictValue; }
    
    public String getDictLabel() { return dictLabel; }
    public void setDictLabel(String dictLabel) { this.dictLabel = dictLabel; }
    
    public String getDictColor() { return dictColor; }
    public void setDictColor(String dictColor) { this.dictColor = dictColor; }
    
    public String getDictIcon() { return dictIcon; }
    public void setDictIcon(String dictIcon) { this.dictIcon = dictIcon; }
    
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }

    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
}
