package com.example.task.repository;

import com.example.task.entity.Task;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {

    Optional<Task> findByTaskCode(String taskCode);

    /**
     * 组合条件查询（所有条件均可为空，空表示不限制）。
     * boards / statuses / modules / owners 为多值精确匹配（in）；keyword 为模糊匹配；
     * deadlineFrom / deadlineTo 为截止日期闭区间。排序由 Sort 参数决定（按 deadline，null 排最后）。
     * 注意：集合参数为 null 时表示不限制，切勿传空集合（JPQL in () 在 MySQL 下为语法错误）。
     */
    @Query("""
            select t from Task t
            where (:boards is null or t.board in :boards)
              and (:statuses is null or t.status in :statuses)
              and (:modules is null or t.module in :modules)
              and (:owners is null or t.owner in :owners)
              and (:deadlineFrom is null or t.deadline >= :deadlineFrom)
              and (:deadlineTo is null or t.deadline <= :deadlineTo)
              and (:keyword is null or :keyword = '' or t.title like concat('%', :keyword, '%')
                    or t.taskCode like concat('%', :keyword, '%')
                    or t.module like concat('%', :keyword, '%')
                    or t.pain like concat('%', :keyword, '%'))
            """)
    List<Task> search(@Param("boards") List<String> boards,
                      @Param("statuses") List<String> statuses,
                      @Param("modules") List<String> modules,
                      @Param("owners") List<String> owners,
                      @Param("deadlineFrom") LocalDate deadlineFrom,
                      @Param("deadlineTo") LocalDate deadlineTo,
                      @Param("keyword") String keyword,
                      Sort sort);

    /** 按 看板 + 工作模块 聚合计数（module 可能为 null）。 */
    @Query("select t.board, t.module, count(t) from Task t group by t.board, t.module")
    List<Object[]> countByBoardModule();

    /** 各事项的跟进记录条数（key: 事项 id）。 */
    @Query("select l.task.id, count(l) from TaskLog l group by l.task.id")
    List<Object[]> countLogsByTask();

    /** 全部子项（按 事项 id → 排序号），用于列表一次性组装，避免逐事项 N+1 查询。 */
    @Query("select s.task.id, s.name from SubItem s order by s.task.id, s.sortOrder")
    List<Object[]> findSubItemNamesByTask();

    /** 负责人去重列表（模糊匹配，q 为空时返回全部，过滤空值）。 */
    @Query("""
            select distinct t.owner from Task t
            where t.owner is not null and t.owner <> ''
              and (:q is null or lower(t.owner) like concat('%', lower(:q), '%'))
            order by t.owner
            """)
    List<String> findOwners(@Param("q") String q);

    /** 指定看板下的工作模块去重列表（模糊匹配，过滤空值）。 */
    @Query("""
            select distinct t.module from Task t
            where t.board = :board and t.module is not null and t.module <> ''
              and (:q is null or lower(t.module) like concat('%', lower(:q), '%'))
            order by t.module
            """)
    List<String> findModulesByBoard(@Param("board") String board, @Param("q") String q);

    /** 指定看板下全部事项的 taskCode + module（用于生成下一个事项 ID）。 */
    @Query("select t.taskCode, t.module from Task t where t.board = :board")
    List<Object[]> findCodeAndModuleByBoard(@Param("board") String board);
}
