package com.example.task.repository;

import com.example.task.entity.NotifyLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotifyLogRepository extends JpaRepository<NotifyLog, Long> {

    List<NotifyLog> findByTaskIdOrderByCreatedAtDesc(Long taskId);
}
