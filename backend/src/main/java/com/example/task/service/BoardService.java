package com.example.task.service;

import com.example.task.dto.BoardDtos;
import com.example.task.entity.Board;
import com.example.task.repository.BoardRepository;
import com.example.task.repository.TaskRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 看板业务逻辑（t_board）：列表 / 新增 / 编辑 / 删除 / 侧栏排序。
 */
@Service
public class BoardService {

    /** 代码格式：小写字母/数字/下划线/中划线。 */
    private static final java.util.regex.Pattern CODE_RE = java.util.regex.Pattern.compile("^[a-z0-9_-]{1,32}$");
    /** 前缀格式：1-2 位大写字母。 */
    private static final java.util.regex.Pattern PREFIX_RE = java.util.regex.Pattern.compile("^[A-Z]{1,2}$");

    private final BoardRepository boardRepository;
    private final TaskRepository taskRepository;

    public BoardService(BoardRepository boardRepository, TaskRepository taskRepository) {
        this.boardRepository = boardRepository;
        this.taskRepository = taskRepository;
    }

    /** 全部看板（按侧栏排序），含每看板事项数。 */
    @Transactional(readOnly = true)
    public List<BoardDtos.BoardItem> list() {
        Map<String, Long> cntByBoard = taskRepository.countByBoard().stream()
                .collect(Collectors.toMap(r -> (String) r[0], r -> (Long) r[1]));
        return boardRepository.findAllByOrderBySortOrderAsc().stream()
                .map(b -> toItem(b, cntByBoard.getOrDefault(b.getCode(), 0L)))
                .toList();
    }

    @Transactional
    public BoardDtos.BoardItem create(BoardDtos.BoardRequest req) {
        if (req == null || req.code() == null || req.code().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "看板代码必填");
        }
        String code = req.code().trim().toLowerCase();
        if (!CODE_RE.matcher(code).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "看板代码需为 1-32 位小写字母/数字/下划线/中划线");
        }
        if (boardRepository.existsByCode(code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "看板代码已存在: " + code);
        }
        String name = normalize(req.name());
        if (name == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "看板名称必填");
        }
        String prefix = normalize(req.prefix());
        if (prefix == null || !PREFIX_RE.matcher(prefix).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "事项前缀需为 1-2 位大写字母（如 QF/LS）");
        }
        if (boardRepository.existsByPrefix(prefix)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "事项前缀已被占用: " + prefix);
        }
        Board board = new Board();
        board.setCode(code);
        board.setName(name);
        board.setAccent(normalizeHex(req.accent(), "#2B59C3"));
        board.setPrefix(prefix);
        board.setSortOrder(req.sortOrder() == null ? maxSortOrder() + 1 : req.sortOrder());
        return toItem(boardRepository.save(board), 0L);
    }

    @Transactional
    public BoardDtos.BoardItem update(Long id, BoardDtos.BoardRequest req) {
        Board board = requireBoard(id);
        // code 不可改：看板身份由 code 决定（业务校验、菜单分组、卡片筛选均依赖 code）
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        String name = normalize(req.name());
        if (name != null) {
            board.setName(name);
        }
        String prefix = normalize(req.prefix());
        if (prefix != null) {
            if (!PREFIX_RE.matcher(prefix).matches()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "事项前缀需为 1-2 位大写字母（如 QF/LS）");
            }
            boardRepository.findByPrefix(prefix).filter(b -> !b.getId().equals(id))
                    .ifPresent(b -> {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "事项前缀已被占用: " + prefix);
                    });
            board.setPrefix(prefix);
        }
        if (req.accent() != null && !req.accent().isBlank()) {
            board.setAccent(normalizeHex(req.accent(), board.getAccent()));
        }
        if (req.sortOrder() != null) {
            board.setSortOrder(req.sortOrder());
        }
        return toItem(boardRepository.save(board), taskRepository.countByBoard().stream()
                .collect(Collectors.toMap(r -> (String) r[0], r -> (Long) r[1]))
                .getOrDefault(board.getCode(), 0L));
    }

    @Transactional
    public void delete(Long id) {
        Board board = requireBoard(id);
        if (board.isSystemFlag()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "系统看板不可删除");
        }
        long count = taskRepository.countByBoard().stream()
                .collect(Collectors.toMap(r -> (String) r[0], r -> (Long) r[1]))
                .getOrDefault(board.getCode(), 0L);
        if (count > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "该看板下有 " + count + " 项事项，先移动或删除事项后再删除看板");
        }
        boardRepository.delete(board);
    }

    /** 按 ids 顺序写侧栏排序（未传入的看板保持原相对位置不动）。 */
    @Transactional
    public void reorder(BoardDtos.BoardReorderRequest req) {
        if (req == null || req.ids() == null || req.ids().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "看板顺序不能为空");
        }
        Map<Long, Board> byId = boardRepository.findAll().stream()
                .collect(Collectors.toMap(Board::getId, Function.identity()));
        for (int i = 0; i < req.ids().size(); i++) {
            Board b = byId.get(req.ids().get(i));
            if (b != null) {
                b.setSortOrder(i);
            }
        }
        boardRepository.saveAll(byId.values());
    }

    /** 供其他服务按 code 取看板（不存在返回 null）。 */
    @Transactional(readOnly = true)
    public Board findByCode(String code) {
        return code == null ? null : boardRepository.findByCode(code).orElse(null);
    }

    /** 全部看板 code（供导入校验等）。 */
    @Transactional(readOnly = true)
    public List<String> allCodes() {
        return boardRepository.findAllByOrderBySortOrderAsc().stream().map(Board::getCode).toList();
    }

    private int maxSortOrder() {
        return boardRepository.findAll().stream().mapToInt(Board::getSortOrder).max().orElse(-1);
    }

    private String normalizeHex(String s, String fallback) {
        String v = normalize(s);
        if (v == null) {
            return fallback;
        }
        if (v.matches("^#[0-9A-Fa-f]{6}$")) {
            return v;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "配色需为 #RRGGBB 格式，如 #2B59C3");
    }

    private String normalize(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private Board requireBoard(Long id) {
        return boardRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "看板不存在: " + id));
    }

    private BoardDtos.BoardItem toItem(Board b, Long taskCount) {
        return new BoardDtos.BoardItem(
                b.getId(), b.getCode(), b.getName(), b.getAccent(), b.getPrefix(),
                b.getSortOrder(), b.isSystemFlag(), taskCount);
    }
}
