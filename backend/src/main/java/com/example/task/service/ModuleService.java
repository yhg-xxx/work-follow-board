package com.example.task.service;

import com.example.task.dto.ModuleDtos;
import com.example.task.entity.Module;
import com.example.task.repository.BoardRepository;
import com.example.task.repository.ModuleRepository;
import com.example.task.repository.TaskRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工作模块注册表业务逻辑（t_module）：列表 / 新建 / 重命名 / 删除。
 * 重命名与删除会同步批量更新 t_task.module 文本字段（仅作用于当前看板）。
 */
@Service
public class ModuleService {

    private final ModuleRepository moduleRepository;
    private final BoardRepository boardRepository;
    private final TaskRepository taskRepository;

    public ModuleService(ModuleRepository moduleRepository,
                         BoardRepository boardRepository,
                         TaskRepository taskRepository) {
        this.moduleRepository = moduleRepository;
        this.boardRepository = boardRepository;
        this.taskRepository = taskRepository;
    }

    /** 指定看板的注册表模块列表（含每模块事项数）。board 为空时返回全部。 */
    @Transactional(readOnly = true)
    public List<ModuleDtos.ModuleItem> list(String board) {
        Map<String, Long> cntByBoardModule = taskRepository.countByBoardModule().stream()
                .collect(Collectors.toMap(
                        r -> boardModuleKey((String) r[0], r[1] == null ? null : ((String) r[1]).trim()),
                        r -> (Long) r[2],
                        Long::sum));
        List<Module> modules = (board == null || board.isBlank())
                ? moduleRepository.findAll()
                : moduleRepository.findAllByBoardOrderBySortOrderAsc(board.trim());
        return modules.stream()
                .map(m -> new ModuleDtos.ModuleItem(
                        m.getId(), m.getBoard(), m.getName(), m.getSortOrder(),
                        cntByBoardModule.getOrDefault(boardModuleKey(m.getBoard(), m.getName()), 0L)))
                .toList();
    }

    @Transactional
    public ModuleDtos.ModuleItem create(ModuleDtos.ModuleRequest req) {
        if (req == null || req.board() == null || req.board().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "看板不能为空");
        }
        String board = req.board().trim();
        boardRepository.findByCode(board)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "看板不存在: " + board));
        String name = requireName(req.name());
        if (moduleRepository.existsByBoardAndName(board, name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该看板下已存在同名模块: " + name);
        }
        Module m = new Module();
        m.setBoard(board);
        m.setName(name);
        m.setSortOrder(maxSortOrder(board) + 1);
        Module saved = moduleRepository.save(m);
        return new ModuleDtos.ModuleItem(saved.getId(), saved.getBoard(), saved.getName(), saved.getSortOrder(), 0L);
    }

    /** 重命名：注册表改名（若存在）+ 批量更新该看板下同名模块事项。目标同名 → 400 拒绝。 */
    @Transactional
    public ModuleDtos.ModuleItem rename(ModuleDtos.ModuleRenameRequest req) {
        if (req == null || req.board() == null || req.board().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "看板不能为空");
        }
        String board = req.board().trim();
        String from = requireName(req.from());
        String to = requireName(req.to());
        if (from.equals(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新名称与旧名称相同");
        }
        if (existsAny(board, to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该看板下已存在同名模块: " + to);
        }
        long taskCount = taskRepository.countByBoardAndModule(board, from);
        moduleRepository.findByBoardAndName(board, from).ifPresent(m -> {
            m.setName(to);
            moduleRepository.save(m);
        });
        taskRepository.renameModule(board, from, to);
        return new ModuleDtos.ModuleItem(null, board, to, 0, taskCount);
    }

    /** 删除：删注册表条目（若存在）+ 批量清空该看板下该模块事项的模块字段。返回受影响事项数。 */
    @Transactional
    public long delete(String board, String name) {
        String b = normalizeBoard(board);
        String n = requireName(name);
        moduleRepository.findByBoardAndName(b, n).ifPresent(moduleRepository::delete);
        return taskRepository.clearModule(b, n);
    }

    /** 该看板下是否存在同名模块：注册表命中 或 任务中实际存在（含 0 条以外的场景）。 */
    private boolean existsAny(String board, String name) {
        return moduleRepository.existsByBoardAndName(board, name)
                || taskRepository.countByBoardAndModule(board, name) > 0;
    }

    private String normalizeBoard(String s) {
        if (s == null || s.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "看板不能为空");
        }
        return s.trim();
    }

    private String requireName(String s) {
        if (s == null || s.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模块名称必填");
        }
        String name = s.trim();
        if (name.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模块名称过长（最多 64 字）");
        }
        return name;
    }

    private int maxSortOrder(String board) {
        return moduleRepository.findAllByBoard(board).stream()
                .mapToInt(Module::getSortOrder).max().orElse(-1);
    }

    private String boardModuleKey(String board, String module) {
        return board + "\u0000" + (module == null ? "" : module);
    }
}
