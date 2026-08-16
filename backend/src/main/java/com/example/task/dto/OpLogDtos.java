package com.example.task.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 操作日志请求/响应 DTO。
 */
public final class OpLogDtos {

    private OpLogDtos() {
    }

    /** 记录操作日志请求（action 必填，其余可选） */
    public record OpLogCreateRequest(
            String action,
            String targetType,
            Long targetId,
            String targetCode,
            String detail) {
    }

    /** 操作日志响应 */
    public record OpLogItem(
            Long id,
            String action,
            String targetType,
            Long targetId,
            String targetCode,
            String detail,
            LocalDateTime createdAt) {
    }

    /** 操作日志分页响应（游标分页：nextCursor 为下一页起点，hasMore 表示是否还有更多） */
    public record OpLogPage(
            List<OpLogItem> items,
            boolean hasMore,
            Long nextCursor) {
    }
}
