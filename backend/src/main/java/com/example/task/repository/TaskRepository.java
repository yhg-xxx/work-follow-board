package com.example.task.repository;

import com.example.task.entity.Task;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long>, TaskSearchRepository {

    Optional<Task> findByTaskCode(String taskCode);

    /** 按 看板 + 工作模块 聚合计数（module 可能为 null）。 */
    @Query("select t.board, t.module, count(t) from Task t group by t.board, t.module")
    List<Object[]> countByBoardModule();

    /** 按 看板 聚合计数（key: board code）。 */
    @Query("select t.board, count(t) from Task t group by t.board")
    List<Object[]> countByBoard();

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

    // ---------- 模块管理（侧栏三点菜单）：重命名/删除时批量同步 t_task.module 文本 ----------

    /** 模块重命名：批量把该看板下旧模块名的事项改为新名称。返回受影响条数。 */
    @Modifying
    @Query("update Task t set t.module = :to, t.updatedAt = CURRENT_TIMESTAMP where t.board = :board and t.module = :from")
    int renameModule(@Param("board") String board, @Param("from") String from, @Param("to") String to);

    /** 模块删除：批量清空该看板下该模块事项的模块字段。返回受影响条数。 */
    @Modifying
    @Query("update Task t set t.module = null, t.updatedAt = CURRENT_TIMESTAMP where t.board = :board and t.module = :name")
    int clearModule(@Param("board") String board, @Param("name") String name);

    /** 指定看板下某模块的事项数（模块不存在/无事项返回 0）。 */
    @Query("select count(t) from Task t where t.board = :board and t.module = :name")
    long countByBoardAndModule(@Param("board") String board, @Param("name") String name);

    /** 全部未置顶事项，按调用方传入 Sort 排序（手动序 sort_order，null 排最后 + id 兜底），供区间重排时定位锚点。 */
    @Query("select t from Task t where t.pinned = false")
    List<Task> findAllNonPinned(Sort sort);
}
