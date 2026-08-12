package com.example.task.dto;

import java.time.LocalDateTime;

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
}
