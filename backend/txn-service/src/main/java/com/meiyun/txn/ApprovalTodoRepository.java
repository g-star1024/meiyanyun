package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ApprovalTodoRepository extends JpaRepository<ApprovalTodo, String>, JpaSpecificationExecutor<ApprovalTodo> {

    List<ApprovalTodo> findAllByOrderBySubmittedAtDesc();

    List<ApprovalTodo> findByStatusOrderBySubmittedAtDesc(String status);

    List<ApprovalTodo> findByStatusNotOrderBySubmittedAtDesc(String status);

    List<ApprovalTodo> findByBizTypeOrderBySubmittedAtDesc(String bizType);

    List<ApprovalTodo> findByBizTypeAndStatusOrderBySubmittedAtDesc(String bizType, String status);

    List<ApprovalTodo> findByBizTypeAndStatusNotOrderBySubmittedAtDesc(String bizType, String status);

    List<ApprovalTodo> findByBizNo(String bizNo);

    /** 当日单号最大序号（AP+yyyyMMdd- 后 6 位；substring(no from 12) 对齐既有 RF/CC/WO 序列写法）。 */
    @Query(value = "select coalesce(max(cast(substring(todo_no from 12) as bigint)), 0) "
            + "from approval_todo where todo_no like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
