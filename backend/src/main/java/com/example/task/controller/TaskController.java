package com.example.task.controller;

import com.example.task.dto.MenuStatsDtos;
import com.example.task.dto.TaskDtos;
import com.example.task.service.TaskService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 事项管理 REST 接口。
 */
@RestController
@RequestMapping("/api")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping("/tasks")
    public List<TaskDtos.TaskListItem> list(@RequestParam(name = "board", required = false) List<String> boards,
                                            @RequestParam(name = "status", required = false) List<String> statuses,
                                            @RequestParam(name = "module", required = false) List<String> modules,
                                            @RequestParam(name = "owner", required = false) List<String> owners,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String deadlineFrom,
                                            @RequestParam(required = false) String deadlineTo,
                                            @RequestParam(required = false) String sortOrder) {
        return taskService.list(boards, statuses, modules, owners, keyword, deadlineFrom, deadlineTo, sortOrder);
    }

    @GetMapping("/tasks/menu-stats")
    public MenuStatsDtos.MenuStats menuStats() {
        return taskService.menuStats();
    }

    @GetMapping("/tasks/owners")
    public List<String> owners(@RequestParam(required = false) String q) {
        return taskService.owners(q);
    }

    @GetMapping("/tasks/modules")
    public List<String> modules(@RequestParam(required = false) String board,
                                @RequestParam(required = false) String q) {
        return taskService.modules(board, q);
    }

    @GetMapping("/tasks/next-code")
    public Map<String, String> nextCode(@RequestParam(required = false) String board,
                                        @RequestParam(required = false) String module) {
        return Map.of("code", taskService.nextCode(board, module));
    }

    @GetMapping("/tasks/{id}")
    public TaskDtos.TaskDetail detail(@PathVariable Long id) {
        return taskService.detail(id);
    }

    @PostMapping("/tasks")
    public TaskDtos.TaskDetail create(@RequestBody TaskDtos.TaskRequest req) {
        return taskService.create(req);
    }

    @PostMapping("/tasks/import")
    public Map<String, Object> importTasks(@RequestBody TaskDtos.TaskImportRequest req) {
        return Map.of("imported", taskService.importAll(req));
    }

    /**
     * 批量导入（新接口）：支持 overwrite 全量覆盖 / upsert 增量合并，返回详细统计。
     * 前端统一使用此接口，旧 /tasks/import 为兼容保留。
     */
    @PostMapping("/tasks/import-batch")
    public TaskDtos.ImportBatchResult importBatch(@RequestBody TaskDtos.ImportBatchRequest req) {
        return taskService.importBatch(req);
    }

    @PutMapping("/tasks/{id}")
    public TaskDtos.TaskDetail update(@PathVariable Long id, @RequestBody TaskDtos.TaskRequest req) {
        return taskService.update(id, req);
    }

    @DeleteMapping("/tasks/{id}")
    public void delete(@PathVariable Long id) {
        taskService.delete(id);
    }

    @PatchMapping("/tasks/{id}/status")
    public TaskDtos.TaskDetail transition(@PathVariable Long id,
                                          @RequestParam String status) {
        return taskService.transition(id, status);
    }

    @PostMapping("/tasks/{id}/logs")
    public TaskDtos.TaskDetail addLog(@PathVariable Long id,
                                      @RequestBody TaskDtos.TaskLogRequest req) {
        return taskService.addLog(id, req);
    }

    @DeleteMapping("/tasks/{id}/logs/{logId}")
    public void deleteLog(@PathVariable Long id,
                          @PathVariable Long logId) {
        taskService.deleteLog(id, logId);
    }

    @PostMapping("/tasks/{id}/notify")
    public TaskDtos.TaskDetail notify(@PathVariable Long id,
                                      @RequestParam(required = false) String scene) {
        return taskService.notify(id, scene);
    }

    @GetMapping("/notify-logs")
    public List<TaskDtos.NotifyLogItem> notifyLogs(@RequestParam Long taskId) {
        if (taskId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "taskId 必填");
        }
        return taskService.notifyLogs(taskId);
    }
}
