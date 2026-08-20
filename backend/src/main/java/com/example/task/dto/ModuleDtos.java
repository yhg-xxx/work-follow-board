package com.example.task.dto;

import java.util.List;

/**
 * 工作模块（t_module 注册表）相关 DTO。
 */
public final class ModuleDtos {

    private ModuleDtos() {
    }

    /** 模块条目：注册表信息 + 该模块下事项数（可为 0）。 */
    public record ModuleItem(Long id, String board, String name, int sortOrder, long taskCount) {
    }

    /** 新建模块：看板 code + 模块名。 */
    public record ModuleRequest(String board, String name) {
    }

    /** 重命名模块：看板 + 旧名 + 新名（按名操作，兼容注册表与任务中实际出现的模块）。 */
    public record ModuleRenameRequest(String board, String from, String to) {
    }

    /** 重排模块：看板 code + 模块名新顺序（侧栏完整可见列表；未注册的模块名会自动注册以记住顺序）。 */
    public record ModuleReorderRequest(String board, List<String> names) {
    }
}
