package com.meiyun.store.handover;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HandoverTodoRepository extends JpaRepository<HandoverTodo, Long> {

    List<HandoverTodo> findByHoIdOrderById(Long hoId);
}
