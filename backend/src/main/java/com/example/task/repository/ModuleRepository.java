package com.example.task.repository;

import com.example.task.entity.Module;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModuleRepository extends JpaRepository<Module, Long> {

    List<Module> findAllByBoardOrderBySortOrderAsc(String board);

    List<Module> findAllByBoard(String board);

    Optional<Module> findByBoardAndName(String board, String name);

    boolean existsByBoardAndName(String board, String name);
}
