package com.example.task.dto;

import java.util.List;

/**
 * 侧边栏菜单聚合统计相关 DTO（供前端菜单计数 / 底部统计使用，避免全量拉取）。
 */
public final class MenuStatsDtos {

    private MenuStatsDtos() {
    }

    /** 菜单统计：全部数量 + 各看板分组 + 临时事项聚合 */
    public record MenuStats(long allCount, List<GroupStat> groups, GroupStat temp) {
    }

    /** 分组统计：分组 id + 数量 + 模块列表 */
    public record GroupStat(String id, long count, List<ModuleStat> modules) {
    }

    /** 模块统计：模块名 + 数量 */
    public record ModuleStat(String name, long count) {
    }
}
