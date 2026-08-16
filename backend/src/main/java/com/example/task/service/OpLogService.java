package com.example.task.service;

import com.example.task.dto.OpLogDtos;
import com.example.task.entity.OpLog;
import com.example.task.repository.OpLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 前端操作日志业务逻辑。
 */
@Service
public class OpLogService {

    private static final int MAX_LIMIT = 500;
    private static final int DEFAULT_LIMIT = 300;

    private final OpLogRepository opLogRepository;

    public OpLogService(OpLogRepository opLogRepository) {
        this.opLogRepository = opLogRepository;
    }

    /** 写入一条操作日志。 */
    @Transactional
    public OpLogDtos.OpLogItem create(OpLogDtos.OpLogCreateRequest req) {
        if (req == null || req.action() == null || req.action().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action 必填");
        }
        // 与 t_op_log 列长度一致，超长直接 400 而非数据库 500
        checkLen(req.action(), 32, "action");
        checkLen(req.targetType(), 16, "targetType");
        checkLen(req.targetCode(), 32, "targetCode");
        checkLen(req.detail(), 512, "detail");
        OpLog log = new OpLog();
        log.setAction(req.action().trim());
        log.setTargetType(req.targetType());
        log.setTargetId(req.targetId());
        log.setTargetCode(req.targetCode());
        log.setDetail(req.detail());
        log.setCreatedAt(LocalDateTime.now());
        return toItem(opLogRepository.save(log));
    }

    private void checkLen(String s, int max, String field) {
        if (s != null && s.length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + "过长（最多 " + max + " 字）");
        }
    }

    /**
     * 游标分页查询：最新在前，cursor 为上一页最后一条的 id（按 id 倒序，更小的 id 是更早的记录），
     * limit 控制单页条数（默认 300，上限 500）。多取一条判断是否还有下一页，nextCursor 供下次请求携带。
     */
    @Transactional(readOnly = true)
    public OpLogDtos.OpLogPage list(List<String> actions, String keyword,
                                    String dateFrom, String dateTo, Long cursor, Integer limit) {
        List<String> act = (actions == null || actions.isEmpty()) ? null
                : actions.stream().filter(a -> a != null && !a.isBlank()).map(String::trim).toList();
        int size = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
        // 多取一条用于判断是否还有下一页
        List<OpLog> rows = opLogRepository.search(act, normalize(keyword),
                        parseDateFrom(dateFrom), parseDateTo(dateTo), cursor, PageRequest.of(0, size + 1));
        boolean hasMore = rows.size() > size;
        if (hasMore) rows = rows.subList(0, size);
        List<OpLogDtos.OpLogItem> items = rows.stream().map(this::toItem).toList();
        Long nextCursor = items.isEmpty() ? null : items.get(items.size() - 1).id();
        return new OpLogDtos.OpLogPage(items, hasMore, nextCursor);
    }

    private OpLogDtos.OpLogItem toItem(OpLog log) {
        return new OpLogDtos.OpLogItem(
                log.getId(), log.getAction(), log.getTargetType(), log.getTargetId(),
                log.getTargetCode(), log.getDetail(), log.getCreatedAt());
    }

    private String normalize(String s) {
        return s == null ? null : s.trim();
    }

    /** dateFrom 参数（yyyy-MM-dd）→ 当天 00:00:00。 */
    private LocalDateTime parseDateFrom(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim()).atStartOfDay();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** dateTo 参数（yyyy-MM-dd）→ 当天 23:59:59.999999999。 */
    private LocalDateTime parseDateTo(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim()).atTime(LocalTime.MAX);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
