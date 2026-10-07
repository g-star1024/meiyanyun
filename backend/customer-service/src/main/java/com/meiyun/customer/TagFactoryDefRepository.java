package com.meiyun.customer;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagFactoryDefRepository extends JpaRepository<TagFactoryDef, Long> {

    /** 名称查重（业务唯一，冲突 409）。 */
    boolean existsByName(String name);

    /** 编码查重（大小写不敏感；DB 另有 uk_tag_factory_code 精确约束兜底，服务层先查返中文 409）。 */
    boolean existsByCodeIgnoreCase(String code);

    /** id 升序（种子插入序=前端 mock 数组序，id 自增天然保序）。 */
    List<TagFactoryDef> findAllByOrderByIdAsc();
}
