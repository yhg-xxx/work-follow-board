package com.example.task.controller;

import com.example.task.dto.OpLogDtos;
import com.example.task.service.OpLogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 前端操作日志 REST 接口。
 */
@RestController
@RequestMapping("/api/op-logs")
public class OpLogController {

    private final OpLogService opLogService;

    public OpLogController(OpLogService opLogService) {
        this.opLogService = opLogService;
    }

    @PostMapping
    public OpLogDtos.OpLogItem create(@RequestBody OpLogDtos.OpLogCreateRequest req) {
        return opLogService.create(req);
    }

    @GetMapping
    public OpLogDtos.OpLogPage list(@RequestParam(name = "action", required = false) List<String> actions,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) String dateFrom,
                                    @RequestParam(required = false) String dateTo,
                                    @RequestParam(required = false) Long cursor,
                                    @RequestParam(required = false) Integer limit) {
        return opLogService.list(actions, keyword, dateFrom, dateTo, cursor, limit);
    }
}
