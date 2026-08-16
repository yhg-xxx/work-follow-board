package com.example.task.repository;

import com.example.task.entity.Board;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BoardRepository extends JpaRepository<Board, Long> {

    List<Board> findAllByOrderBySortOrderAsc();

    Optional<Board> findByCode(String code);

    Optional<Board> findByPrefix(String prefix);

    boolean existsByCode(String code);

    boolean existsByPrefix(String prefix);
}
