package com.meiyun.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "io_task")
public class IoTask {

  public static final String TYPE_IMPORT = "IMPORT";
  public static final String TYPE_EXPORT = "EXPORT";
  public static final String STATUS_PENDING = "PENDING";
  public static final String STATUS_VALIDATING = "VALIDATING";
  public static final String STATUS_DONE = "DONE";
  public static final String STATUS_FAILED = "FAILED";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "task_no", nullable = false, unique = true, length = 32)
  private String taskNo;

  @Column(name = "type", nullable = false, length = 8)
  private String type;

  @Column(name = "status", nullable = false, length = 16)
  private String status;

  @Column(name = "scope", length = 16)
  private String scope;

  @Column(name = "file_name", length = 255)
  private String fileName;

  @Column(name = "file_hash", length = 64)
  private String fileHash;

  @Column(name = "total_count")
  private Integer totalCount;

  @Column(name = "success_count")
  private Integer successCount;

  @Column(name = "fail_count")
  private Integer failCount;

  @Column(name = "errors", columnDefinition = "jsonb")
  private String errors;

  @Column(name = "mask_phone")
  private Boolean maskPhone;

  @Column(name = "mask_id_card")
  private Boolean maskIdCard;

  @Column(name = "store_code", length = 16)
  private String storeCode;

  @Column(name = "created_by", length = 64)
  private String createdBy;

  @Column(name = "created_at")
  private OffsetDateTime createdAt;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  @PrePersist
  void prePersist() {
    OffsetDateTime now = OffsetDateTime.now();
    if (status == null) {
      status = STATUS_PENDING;
    }
    if (totalCount == null) {
      totalCount = 0;
    }
    if (successCount == null) {
      successCount = 0;
    }
    if (failCount == null) {
      failCount = 0;
    }
    if (maskPhone == null) {
      maskPhone = Boolean.TRUE;
    }
    if (maskIdCard == null) {
      maskIdCard = Boolean.TRUE;
    }
    if (createdAt == null) {
      createdAt = now;
    }
    if (updatedAt == null) {
      updatedAt = now;
    }
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = OffsetDateTime.now();
  }
}
