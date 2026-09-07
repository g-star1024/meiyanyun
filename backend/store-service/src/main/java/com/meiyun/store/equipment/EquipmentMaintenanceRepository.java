package com.meiyun.store.equipment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EquipmentMaintenanceRepository extends JpaRepository<EquipmentMaintenance, Long> {

    /** 某设备的校准/维保/维修记录：发生日期倒序，同日按 id 倒序（新记录在前，与前端 unshift 一致）。 */
    List<EquipmentMaintenance> findByEquipmentIdOrderByOccurredAtDescIdDesc(Long equipmentId);
}
