package com.example.task.controller;

import com.example.task.dto.ModuleDtos;
import com.example.task.service.ModuleService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工作模块注册表 REST 接口（侧栏三点菜单的模块管理）。
 */
@RestController
@RequestMapping("/api/modules")
public class ModuleController {

    private final ModuleService moduleService;

    public ModuleController(ModuleService moduleService) {
        this.moduleService = moduleService;
    }

    @GetMapping
    public List<ModuleDtos.ModuleItem> list(@RequestParam(required = false) String board) {
        return moduleService.list(board);
    }

    @PostMapping
    public ModuleDtos.ModuleItem create(@RequestBody ModuleDtos.ModuleRequest req) {
        return moduleService.create(req);
    }

    @PutMapping("/rename")
    public ModuleDtos.ModuleItem rename(@RequestBody ModuleDtos.ModuleRenameRequest req) {
        return moduleService.rename(req);
    }

    @DeleteMapping
    public long delete(@RequestParam String board, @RequestParam String name) {
        return moduleService.delete(board, name);
    }
}
