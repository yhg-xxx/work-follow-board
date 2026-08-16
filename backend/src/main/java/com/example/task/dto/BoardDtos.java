package com.example.task.dto;

import java.util.List;

/**
 * 看板相关请求/响应 DTO。
 */
public final class BoardDtos {

    private BoardDtos() {
    }

    /** 看板列表项（含该看板下事项数） */
    public record BoardItem(
            Long id,
            String code,
            String name,
            String accent,
            String prefix,
            int sortOrder,
            boolean systemFlag,
            long taskCount) {
    }

    /** 创建看板请求（code 必填且唯一；新看板无 systemFlag，默认为非系统看板） */
    public record BoardRequest(
            String code,
            String name,
            String accent,
            String prefix,
            Integer sortOrder) {
    }

    /** 看板重排请求：ids 顺序即新的侧栏排序 */
    public record BoardReorderRequest(List<Long> ids) {
    }
}
