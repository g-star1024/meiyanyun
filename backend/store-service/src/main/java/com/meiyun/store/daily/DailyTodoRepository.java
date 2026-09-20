package com.meiyun.store.daily;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DailyTodoRepository extends JpaRepository<DailyTodo, Long> {

    List<DailyTodo> findByDrIdOrderById(Long drId);
}
