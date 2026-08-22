package com.example.task.repository;

import com.example.task.entity.Task;

import java.time.LocalDate;
import java.util.List;

/**
 * 组合条件分页查询（keyset 游标）与统计聚合。
 * 排序全部在后端完成（pinned 恒最前），按游标取“该行之后”的一页，避免全量拉取。
 */
public interface TaskSearchRepository {

    /**
     * 游标值（服务层解码后传入；k1/k2 已按排序模式转为对应类型：Integer 或 LocalDate）。
     * pinned 为 Boolean；id 恒非空。
     */
    record SearchCursor(Boolean pinned, Object k1, Object k2, Long id) {
    }

    /**
     * 分页查询一页（多取一条用于判断 hasMore）。
     * sortMode: asc / desc / manual / priority / updateDate（默认 asc）。
     * cursor 为 null 表示首页；limit 为单页条数（all 模式下传极大值即可）。
     * ownerFuzzy 为 true 时 owners 按 LIKE %词% 模糊匹配（多值 OR），否则精确 IN。
     */
    List<Task> searchPage(String sortMode,
                          List<String> boards, List<String> statuses, List<String> modules,
                          List<String> owners, boolean ownerFuzzy, String keyword,
                          LocalDate deadlineFrom, LocalDate deadlineTo,
                          SearchCursor cursor, int limit);

    /** 当前筛选范围的条数（统计条带 total / 全选判定）。 */
    long countTotal(List<String> boards, List<String> statuses, List<String> modules,
                    List<String> owners, boolean ownerFuzzy, String keyword,
                    LocalDate deadlineFrom, LocalDate deadlineTo);

    /**
     * 当前筛选范围的统计聚合：Object[]{total, urgent, ongoing, high, near}。
     * near = 截止日期在 [today, todayPlus7] 闭区间内的条数。
     */
    Object[] countStats(List<String> boards, List<String> statuses, List<String> modules,
                        List<String> owners, boolean ownerFuzzy, String keyword,
                        LocalDate deadlineFrom, LocalDate deadlineTo,
                        LocalDate today, LocalDate todayPlus7);
}
