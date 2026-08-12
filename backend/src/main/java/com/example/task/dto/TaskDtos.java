package com.example.task.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 事项相关请求/响应 DTO。
 */
public final class TaskDtos {

    private TaskDtos() {
    }

    /** 创建/更新事项请求 */
    public record TaskRequest(
            String taskCode,
            String board,
            String module,
            String title,
            String description,
            String status,
            String priority,
            String owner,
            String ownerUserid,
            String collab,
            String pain,
            String nextStep,
            LocalDate deadline,
            String risk,
            List<String> subItems,
            List<TaskLogRequest> logs) {
    }

    /** 跟进记录请求 */
    public record TaskLogRequest(LocalDate logDate, String person, String summary, String nextStep) {
    }

    /** 列表项（含卡片展示所需全部字段；跟进记录 logs 仅在详情/抽屉时返回） */
    public record TaskListItem(
            Long id,
            String taskCode,
            String board,
            String module,
            String title,
            String description,
            String status,
            String priority,
            String owner,
            String ownerUserid,
            String collab,
            String pain,
            String nextStep,
            LocalDate deadline,
            String risk,
            String notifyStatus,
            LocalDate updateDate,
            LocalDateTime updatedAt,
            List<String> subItems,
            Long logCount) {
    }

    /** 跟进记录响应 */
    public record TaskLogItem(Long id, LocalDate logDate, String person, String summary, String nextStep) {
    }

    /** 事项详情 */
    public record TaskDetail(
            Long id,
            String taskCode,
            String board,
            String module,
            String title,
            String description,
            String status,
            String priority,
            String owner,
            String ownerUserid,
            String collab,
            String pain,
            String nextStep,
            LocalDate deadline,
            String risk,
            String notifyStatus,
            LocalDate updateDate,
            List<String> subItems,
            List<TaskLogItem> logs) {
    }

    /** 全量覆盖导入请求（对应看板导出 JSON 的 data.quanfa / data.happy） */
    public record TaskImportRequest(
            List<TaskImportItem> quanfa,
            List<TaskImportItem> happy) {
    }

    /** 导入的事项条目（字段对齐看板 JSON，日期均为字符串便于原样解析） */
    public record TaskImportItem(
            String id,
            String module,
            String item,
            String status,
            String priority,
            String owner,
            String collab,
            String pain,
            String next,
            String deadline,
            String risk,
            List<String> subItems,
            List<TaskImportLog> logs,
            String deadlineMonth,
            String updateDate) {
    }

    /** 导入的跟进记录 */
    public record TaskImportLog(String date, String person, String summary, String next) {
    }

    /** 推送记录响应 */
    public record NotifyLogItem(Long id, Long taskId, String touser, String content,
                                String result, Integer errcode, String errmsg, LocalDateTime createdAt) {
    }

    // ---------- 批量导入（新接口 /tasks/import-batch） ----------

    /** 批量导入的跟进记录 */
    public record TaskBatchLog(String date, String person, String summary, String next) {
    }

    /**
     * 批量导入的单个事项（平面结构，用 board 字段区分看板，不再分 quanfa/happy 两个数组）。
     * 字段名对齐 TaskRequest，便于代码复用。
     */
    public record TaskBatchItem(
            String taskCode,
            String board,           // "quanfa" | "happy"（必填）
            String module,
            String title,           // 具体事项/标题（必填）
            String description,
            String status,
            String priority,
            String owner,
            String collab,
            String pain,
            String nextStep,
            String deadline,
            String risk,
            List<String> subItems,
            List<TaskBatchLog> logs,
            String updateDate) {
    }

    /**
     * 批量导入请求。
     * mode: "overwrite" 全量覆盖（默认，删全库再插入）；"upsert" 增量合并（按 taskCode 匹配，存在则更新，不存在则新增）
     * skipOnError: true=跳过校验失败的行继续导入；false=遇到错误立即中断（默认）
     */
    public record ImportBatchRequest(
            String mode,
            Boolean skipOnError,
            List<TaskBatchItem> items) {
    }

    /** 单条导入错误（按行号+字段定位） */
    public record ImportBatchError(
            Integer rowIndex,
            String field,
            String message,
            String value) {
    }

    /** 批量导入结果 */
    public record ImportBatchResult(
            int imported,
            int updated,
            int skipped,
            int total,
            List<ImportBatchError> errors) {
    }
}
