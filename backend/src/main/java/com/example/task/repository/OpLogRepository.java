package com.example.task.repository;

import com.example.task.entity.OpLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OpLogRepository extends JpaRepository<OpLog, Long> {

    /**
     * 组合条件查询（全部可选）：action 多值精确匹配；keyword 模糊匹配 detail / target_code；
     * dateFrom / dateTo 为 created_at 闭区间；cursor 为上一页最后一条的 id（游标分页，只取 id 更小的更早记录）。
     * 按 id 倒序（最新在前），条数由 Pageable 控制。
     */
    @Query("""
            select o from OpLog o
            where (:actions is null or o.action in :actions)
              and (:keyword is null or :keyword = '' or o.detail like concat('%', :keyword, '%')
                    or o.targetCode like concat('%', :keyword, '%'))
              and (:dateFrom is null or o.createdAt >= :dateFrom)
              and (:dateTo is null or o.createdAt <= :dateTo)
              and (:cursor is null or o.id < :cursor)
            order by o.id desc
            """)
    List<OpLog> search(@Param("actions") List<String> actions,
                       @Param("keyword") String keyword,
                       @Param("dateFrom") LocalDateTime dateFrom,
                       @Param("dateTo") LocalDateTime dateTo,
                       @Param("cursor") Long cursor,
                       Pageable pageable);
}
