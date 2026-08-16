package com.example.task.dto;

import java.util.List;

/**
 * 侧边栏菜单聚合统计相关 DTO（供前端菜单计数 / 底部统计使用，避免全量拉取）。
 */
public final class MenuStatsDtos {

    private MenuStatsDtos() {
    }

    /** 菜单统计：全部数量 + 各看板分组（看板全部来自 t_board，动态） */
    public record MenuStats(long allCount, List<GroupStat> groups) {
    }

    /** 分组统计：看板 code + 展示信息 + 数量 + 模块列表 */
    public record GroupStat(String id, String label, String accent, String prefix, long count, List<ModuleStat> modules) {
    }

    /** 模块统计：模块名 + 数量 */
    public record ModuleStat(String name, long count) {
    }
}
