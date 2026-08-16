package com.example.task.service;

import com.example.task.dto.TaskDtos;
import com.example.task.dto.MenuStatsDtos;
import com.example.task.entity.Board;
import com.example.task.entity.Module;
import com.example.task.entity.SubItem;
import com.example.task.entity.Task;
import com.example.task.entity.TaskLog;
import com.example.task.repository.BoardRepository;
import com.example.task.repository.ModuleRepository;
import com.example.task.repository.NotifyLogRepository;
import com.example.task.repository.TaskRepository;
import com.example.task.repository.TaskSearchRepository;
import com.example.wecom.WeComApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 事项业务逻辑。
 */
@Service
public class TaskService {

    public static final Set<String> STATUSES =
            Set.of("未启动", "进行中", "亟待解决", "持续跟进", "已完成");
    public static final Set<String> PRIORITIES = Set.of("高", "中", "低");
    /** 事项ID 规律：{看板前缀}-{模块字母}{序号}，如 QF-B03 / HF-C02 / LS-A01；前缀 1-2 位大写字母。 */
    private static final Pattern ID_RE = Pattern.compile("^([A-Z]{1,2})-([A-Z])(\\d+)$");

    private final TaskRepository taskRepository;
    private final BoardRepository boardRepository;
    private final ModuleRepository moduleRepository;
    private final NotifyLogRepository notifyLogRepository;
    private final WeComApiClient weComApiClient;

    public TaskService(TaskRepository taskRepository,
                       BoardRepository boardRepository,
                       ModuleRepository moduleRepository,
                       NotifyLogRepository notifyLogRepository,
                       WeComApiClient weComApiClient) {
        this.taskRepository = taskRepository;
        this.boardRepository = boardRepository;
        this.moduleRepository = moduleRepository;
        this.notifyLogRepository = notifyLogRepository;
        this.weComApiClient = weComApiClient;
    }

    @Transactional(readOnly = true)
    public TaskDtos.TaskPage list(List<String> boards, List<String> statuses, List<String> modules,
                                  List<String> owners, String keyword, String deadlineFrom, String deadlineTo,
                                  String sortMode, String cursor, Integer limit, boolean all) {
        String mode = normalize(sortMode);
        if (!Set.of("asc", "desc", "manual", "priority", "updateDate").contains(mode)) {
            mode = "asc";
        }
        int size = all ? Integer.MAX_VALUE : (limit == null ? 50 : Math.min(Math.max(limit, 1), 200));
        // 多取一条用于判断是否还有下一页（all 模式一次性返回全部）
        int fetch = all ? Integer.MAX_VALUE : size + 1;
        List<Task> tasks = taskRepository.searchPage(
                mode,
                normalizeList(boards), normalizeList(statuses), normalizeList(modules), normalizeList(owners),
                normalize(keyword), parseDate(deadlineFrom), parseDate(deadlineTo),
                decodeCursor(cursor, mode), fetch);
        boolean hasMore = !all && tasks.size() > size;
        if (hasMore) {
            tasks = tasks.subList(0, size);
        }
        // 一次聚合查询拿到各事项的跟进条数，供卡片角标展示
        Map<Long, Long> logCounts = taskRepository.countLogsByTask().stream()
                .collect(Collectors.toMap(r -> (Long) r[0], r -> (Long) r[1]));
        // 一次查询拿到全部子项，按事项 id 分组（保持排序号顺序）
        Map<Long, List<String>> subItemsByTask = taskRepository.findSubItemNamesByTask().stream()
                .collect(Collectors.groupingBy(r -> (Long) r[0],
                        LinkedHashMap::new,
                        Collectors.mapping(r -> (String) r[1], Collectors.toList())));
        List<TaskDtos.TaskListItem> items = tasks.stream()
                .map(t -> toListItem(t,
                        logCounts.getOrDefault(t.getId(), 0L),
                        subItemsByTask.getOrDefault(t.getId(), List.of())))
                .toList();
        // 有下一页时才生成游标（取本页最后一条的排序键值）；all 模式 / 无下一页时为 null
        String nextCursor = (all || !hasMore || items.isEmpty())
                ? null
                : encodeCursor(mode, tasks.get(tasks.size() - 1));
        return new TaskDtos.TaskPage(items, hasMore, nextCursor);
    }

    /** 统计条带聚合（当前筛选范围）。 */
    @Transactional(readOnly = true)
    public TaskDtos.TaskStats stats(List<String> boards, List<String> statuses, List<String> modules,
                                    List<String> owners, String keyword, String deadlineFrom, String deadlineTo) {
        LocalDate today = LocalDate.now();
        Object[] row = taskRepository.countStats(
                normalizeList(boards), normalizeList(statuses), normalizeList(modules), normalizeList(owners),
                normalize(keyword), parseDate(deadlineFrom), parseDate(deadlineTo),
                today, today.plusDays(7));
        return new TaskDtos.TaskStats(
                num(row[0]), num(row[1]), num(row[2]), num(row[3]), num(row[4]));
    }

    private long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    /** 优先级排序权重（与仓储 PRI_RANK 一致）：高=3 / 中=2 / 低=1 / 其他=0。 */
    private int priRank(String p) {
        return switch (p == null ? "" : p) {
            case "高" -> 3;
            case "中" -> 2;
            case "低" -> 1;
            default -> 0;
        };
    }

    // ---------- keyset 游标编解码（不透明 base64url(JSON)，前端原样回传） ----------

    private static final ObjectMapper CURSOR_MAPPER = new ObjectMapper();

    /** 由本页最后一条生成下一页游标；值按排序模式取对应键（日期存字符串、数值存数字）。 */
    private String encodeCursor(String mode, Task t) {
        try {
            Object k1 = switch (mode) {
                case "manual" -> t.getSortOrder();
                case "priority" -> priRank(t.getPriority());
                case "updateDate" -> t.getUpdateDate() == null ? null : t.getUpdateDate().toString();
                default -> t.getDeadline() == null ? null : t.getDeadline().toString();
            };
            Object k2 = (mode.equals("priority") || mode.equals("updateDate"))
                    ? (t.getDeadline() == null ? null : t.getDeadline().toString())
                    : null;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("p", t.isPinned() ? 1 : 0);
            m.put("k1", k1);
            m.put("k2", k2);
            m.put("id", t.getId());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(CURSOR_MAPPER.writeValueAsBytes(m));
        } catch (Exception e) {
            return null;
        }
    }

    /** 解码游标；非法/缺失时返回 null（视为首页）。 */
    private TaskSearchRepository.SearchCursor decodeCursor(String cursor, String mode) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            JsonNode n = CURSOR_MAPPER.readTree(Base64.getUrlDecoder().decode(cursor));
            Boolean pinned = n.path("p").asInt(0) == 1;
            Long id = n.path("id").asLong(0);
            Object k1 = typedCursorValue(mode, n.get("k1"));
            Object k2 = (mode.equals("priority") || mode.equals("updateDate"))
                    ? typedCursorValue("date", n.get("k2"))
                    : null;
            return new TaskSearchRepository.SearchCursor(pinned, k1, k2, id);
        } catch (Exception e) {
            return null;
        }
    }

    /** 游标值转类型：manual/priority 为整数（sortOrder / 优先级权重），其余模式为日期。 */
    private Object typedCursorValue(String kind, JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return switch (kind) {
            case "manual", "priority" -> node.asInt();
            default -> LocalDate.parse(node.asText());
        };
    }

    /**
     * 侧边栏菜单聚合统计：全部数量 + 各看板分组（含模块列表）。
     * 看板全部来自 t_board（按 sort_order 排序），模块计数由 countByBoardModule 聚合；
     * 模块列表 = 注册表（t_module，按 sort_order）∪ 任务实际出现的模块（补集按名称追加）；
     * 空 module 不计入模块子列表，但计入分组总数。
     */
    @Transactional(readOnly = true)
    public MenuStatsDtos.MenuStats menuStats() {
        List<Object[]> rows = taskRepository.countByBoardModule();
        Map<String, Map<String, Long>> byBoard = new HashMap<>();
        long allCount = 0;
        for (Object[] row : rows) {
            String board = (String) row[0];
            String module = row[1] == null ? null : ((String) row[1]).trim();
            long cnt = ((Number) row[2]).longValue();
            allCount += cnt;
            byBoard.computeIfAbsent(board, k -> new LinkedHashMap<>()).merge(module, cnt, Long::sum);
        }

        Map<String, List<Module>> registryByBoard = moduleRepository.findAll().stream()
                .sorted(Comparator.comparingInt(Module::getSortOrder))
                .collect(Collectors.groupingBy(Module::getBoard, LinkedHashMap::new, Collectors.toList()));
        List<MenuStatsDtos.GroupStat> groups = boardRepository.findAllByOrderBySortOrderAsc().stream()
                .map(b -> {
                    Map<String, Long> modMap = byBoard.getOrDefault(b.getCode(), new LinkedHashMap<>());
                    List<Module> registry = registryByBoard.getOrDefault(b.getCode(), List.of());
                    return new MenuStatsDtos.GroupStat(
                            b.getCode(), b.getName(), b.getAccent(), b.getPrefix(),
                            sumValues(modMap), buildModuleStats(modMap, registry));
                })
                .toList();
        return new MenuStatsDtos.MenuStats(allCount, groups);
    }

    /** 负责人去重列表（模糊匹配）。 */
    @Transactional(readOnly = true)
    public List<String> owners(String q) {
        return taskRepository.findOwners(normalize(q));
    }

    /** 指定看板下的工作模块去重列表（模糊匹配）：注册表模块（按 sort_order）∪ 任务实际出现的模块。 */
    @Transactional(readOnly = true)
    public List<String> modules(String board, String q) {
        String b = normalize(board);
        if (b == null) {
            b = "quanfa";
        }
        String kw = normalize(q);
        final String lower = kw == null ? null : kw.toLowerCase();
        Set<String> merged = new LinkedHashSet<>();
        for (Module m : moduleRepository.findAllByBoardOrderBySortOrderAsc(b)) {
            if (lower == null || m.getName().toLowerCase().contains(lower)) {
                merged.add(m.getName());
            }
        }
        for (String name : taskRepository.findModulesByBoard(b, kw)) {
            merged.add(name);
        }
        return List.copyOf(merged);
    }

    /**
     * 生成下一个事项ID：{看板前缀}-{字母}{序号}。
     * 前缀取自 t_board（如 quanfa→QF / happy→HF / temp→LS）；只有匹配该看板前缀的旧码参与统计
     * （如 temp 看板迁移来的 QF-H01~03 不参与新码统计，新码从 LS-A01 起）。
     * 已有模块复用其字母；新模块顺延分配下一个未使用字母；序号取该字母现有最大值 +1。
     */
    @Transactional(readOnly = true)
    public String nextCode(String board, String module) {
        String b = normalize(board);
        if (b == null) {
            b = "quanfa";
        }
        final String boardCode = b;
        Board boardEntity = boardRepository.findByCode(boardCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "看板不存在: " + boardCode));
        String prefix = boardEntity.getPrefix();

        Map<String, String> letterByModule = new HashMap<>();
        Map<String, Integer> maxSeqByLetter = new HashMap<>();
        for (Object[] row : taskRepository.findCodeAndModuleByBoard(b)) {
            String code = (String) row[0];
            String mod = row[1] == null ? null : ((String) row[1]).trim();
            if (code == null) {
                continue;
            }
            Matcher m = ID_RE.matcher(code);
            if (!m.matches()) {
                continue;
            }
            String p = m.group(1);
            String letter = m.group(2);
            int seq = Integer.parseInt(m.group(3));
            if (!prefix.equals(p)) {
                continue;
            }
            if (mod != null && !mod.isBlank()) {
                letterByModule.put(prefix + ":" + mod, letter);
            }
            maxSeqByLetter.merge(letter, seq, Math::max);
        }

        String mod = normalize(module);
        String letter = mod != null ? letterByModule.get(prefix + ":" + mod) : null;
        if (letter == null) {
            for (char c = 'A'; c <= 'Z'; c++) {
                String l = String.valueOf(c);
                if (!maxSeqByLetter.containsKey(l)) {
                    letter = l;
                    break;
                }
            }
        }
        if (letter == null) {
            letter = "A";
        }
        int next = maxSeqByLetter.getOrDefault(letter, 0) + 1;
        return prefix + "-" + letter + String.format("%02d", next);
    }

    private long sumValues(Map<String, Long> m) {
        long s = 0;
        for (Long v : m.values()) {
            s += v;
        }
        return s;
    }

    /** 模块统计列表 = 注册表模块（按 sort_order，计数可为 0）∪ 任务实际出现但未注册的模块（按名称追加）。 */
    private List<MenuStatsDtos.ModuleStat> buildModuleStats(Map<String, Long> taskModMap, List<Module> registry) {
        List<MenuStatsDtos.ModuleStat> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Module m : registry) {
            if (m.getName() == null || m.getName().isBlank() || !seen.add(m.getName())) {
                continue;
            }
            list.add(new MenuStatsDtos.ModuleStat(m.getName(), taskModMap.getOrDefault(m.getName(), 0L)));
        }
        List<MenuStatsDtos.ModuleStat> rest = new ArrayList<>();
        for (Map.Entry<String, Long> e : taskModMap.entrySet()) {
            String name = e.getKey();
            if (name == null || name.isBlank() || seen.contains(name)) {
                continue;
            }
            rest.add(new MenuStatsDtos.ModuleStat(name, e.getValue()));
        }
        rest.sort(Comparator.comparing(MenuStatsDtos.ModuleStat::name));
        list.addAll(rest);
        return list;
    }

    @Transactional(readOnly = true)
    public TaskDtos.TaskDetail detail(Long id) {
        return toDetail(requireTask(id));
    }

    @Transactional
    public TaskDtos.TaskDetail create(TaskDtos.TaskRequest req) {
        validateTaskRequest(req);
        if (req.taskCode() != null && !req.taskCode().isBlank()
                && taskRepository.findByTaskCode(req.taskCode().trim()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "事项ID已存在: " + req.taskCode());
        }
        Task task = new Task();
        applyRequest(task, req);
        task.setTaskCode(req.taskCode() == null ? null : req.taskCode().trim());
        task.setUpdateDate(LocalDate.now());
        taskRepository.save(task);
        weComApiClient.sendTaskNotify(task, "create");
        return toDetail(task);
    }

    @Transactional
    public TaskDtos.TaskDetail update(Long id, TaskDtos.TaskRequest req) {
        validateTaskRequest(req);
        Task task = requireTask(id);
        String oldOwnerUserid = task.getOwnerUserid();
        applyRequest(task, req);
        task.setUpdateDate(LocalDate.now());
        taskRepository.save(task);
        // 负责人变更时重新分配通知
        if (oldOwnerUserid != null && !oldOwnerUserid.equals(task.getOwnerUserid())) {
            weComApiClient.sendTaskNotify(task, "assign");
        }
        return toDetail(task);
    }

    /**
     * 创建/更新事项的边界校验（与导入/状态流转的校验口径一致）：
     * 标题/模块必填、看板必须存在于 t_board、状态/优先级非空时必须是合法枚举。
     */
    private void validateTaskRequest(TaskDtos.TaskRequest req) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        if (req.title() == null || req.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标题不能为空");
        }
        if (req.module() == null || req.module().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工作模块不能为空");
        }
        if (req.board() == null || req.board().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "看板不能为空");
        }
        if (boardRepository.findByCode(req.board().trim()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "看板不存在: " + req.board());
        }
        if (req.status() != null && !req.status().isBlank() && !STATUSES.contains(req.status().trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "非法状态: " + req.status() + "，可选: " + STATUSES);
        }
        if (req.priority() != null && !req.priority().isBlank() && !PRIORITIES.contains(req.priority().trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "非法优先级: " + req.priority() + "，可选: " + PRIORITIES);
        }
    }

    @Transactional
    public void delete(Long id) {
        taskRepository.delete(requireTask(id));
    }

    /**
     * 全量覆盖导入：清空现有全部数据（含子项/跟进记录/推送记录），
     * 再按看板 JSON（quanfa / happy）原样重建。单事务保证要么全部成功、要么全部回滚。
     */
    @Transactional
    public int importAll(TaskDtos.TaskImportRequest req) {
        if (req == null || (isEmpty(req.quanfa()) && isEmpty(req.happy()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "导入数据为空");
        }
        // 子项/跟进记录随 t_task 外键 ON DELETE CASCADE 级联删除；t_notify_log 无外键需显式清空
        notifyLogRepository.deleteAllInBatch();
        taskRepository.deleteAllInBatch();
        int count = 0;
        count += saveImported(req.quanfa(), "quanfa");
        count += saveImported(req.happy(), "happy");
        return count;
    }

    private int saveImported(List<TaskDtos.TaskImportItem> items, String board) {
        if (isEmpty(items)) {
            return 0;
        }
        int count = 0;
        for (TaskDtos.TaskImportItem it : items) {
            if (it == null || it.item() == null || it.item().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "导入数据存在缺少「具体事项」的条目");
            }
            Task task = new Task();
            task.setTaskCode(trimToNull(it.id()));
            task.setBoard(board);
            task.setModule(trimToNull(it.module()));
            task.setTitle(it.item().trim());
            task.setStatus(trimToNull(it.status()) == null ? "未启动" : it.status().trim());
            task.setPriority(trimToNull(it.priority()) == null ? "中" : it.priority().trim());
            task.setOwner(trimToNull(it.owner()));
            task.setCollab(trimToNull(it.collab()));
            task.setPain(trimToNull(it.pain()));
            task.setNextStep(trimToNull(it.next()));
            task.setDeadline(parseDate(it.deadline()));
            task.setRisk(trimToNull(it.risk()));
            task.setDeadlineMonth(trimToNull(it.deadlineMonth()));
            task.setUpdateDate(parseDate(it.updateDate()));
            task.setNotifyStatus("NONE");

            if (it.subItems() != null) {
                int order = 0;
                for (String name : it.subItems()) {
                    if (name == null || name.isBlank()) {
                        continue;
                    }
                    SubItem si = new SubItem();
                    si.setTask(task);
                    si.setName(name.trim());
                    si.setSortOrder(order++);
                    task.getSubItems().add(si);
                }
            }
            if (it.logs() != null) {
                for (TaskDtos.TaskImportLog l : it.logs()) {
                    if (l == null || l.summary() == null || l.summary().isBlank()) {
                        continue;
                    }
                    TaskLog tl = new TaskLog();
                    tl.setTask(task);
                    tl.setLogDate(parseDate(l.date()));
                    tl.setPerson(trimToNull(l.person()));
                    tl.setSummary(l.summary());
                    tl.setNextStep(trimToNull(l.next()));
                    task.getLogs().add(tl);
                }
            }
            taskRepository.save(task);
            count++;
        }
        return count;
    }

    private boolean isEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }

    private String trimToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    // ---------- 批量导入（overwrite / upsert） ----------

    /**
     * 批量导入。
     * mode:
     *   - "overwrite"（默认）：清空全库再插入（同旧 importAll 语义，但返回详细统计）
     *   - "upsert"：按 taskCode 匹配，存在则更新（保留 id，子项/跟进记录重建），不存在则新增
     * skipOnError: true=跳过校验失败的行并收集错误；false=遇到第一条错误立即抛 BAD_REQUEST
     */
    @Transactional
    public TaskDtos.ImportBatchResult importBatch(TaskDtos.ImportBatchRequest req) {
        if (req == null || isEmpty(req.items())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "导入数据为空");
        }
        String mode = req.mode() == null ? "overwrite" : req.mode().trim().toLowerCase();
        if (!Set.of("overwrite", "upsert").contains(mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法 mode：" + mode);
        }
        boolean skipOnError = Boolean.TRUE.equals(req.skipOnError());
        // 看板合法性按 t_board 实时校验
        Set<String> validBoards = boardRepository.findAllByOrderBySortOrderAsc().stream()
                .map(Board::getCode)
                .collect(Collectors.toSet());

        List<TaskDtos.ImportBatchError> errors = new ArrayList<>();
        int imported = 0;
        int updated = 0;
        int skipped = 0;

        if ("overwrite".equals(mode)) {
            notifyLogRepository.deleteAllInBatch();
            taskRepository.deleteAllInBatch();
        }

        List<TaskDtos.TaskBatchItem> items = req.items();
        // upsert 模式按 taskCode 匹配：同一批次内重复的 code 会互相覆盖且计数失真，需去重
        Set<String> seenCodes = "upsert".equals(mode) ? new HashSet<>() : null;
        for (int i = 0; i < items.size(); i++) {
            TaskDtos.TaskBatchItem it = items.get(i);
            List<TaskDtos.ImportBatchError> rowErrs = validateBatchItem(it, i, validBoards);
            if (seenCodes != null && it != null) {
                String code = trimToNull(it.taskCode());
                if (code != null && !seenCodes.add(code)) {
                    rowErrs.add(new TaskDtos.ImportBatchError(i, "事项ID",
                            "批次内存在重复的事项ID: " + code, code));
                }
            }
            if (!rowErrs.isEmpty()) {
                if (skipOnError) {
                    errors.addAll(rowErrs);
                    skipped++;
                    continue;
                } else {
                    TaskDtos.ImportBatchError first = rowErrs.get(0);
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "第 " + (i + 1) + " 行 " + first.field() + "：" + first.message());
                }
            }

            String code = trimToNull(it.taskCode());
            boolean isUpdate = false;
            Task task;
            if ("upsert".equals(mode) && code != null) {
                task = taskRepository.findByTaskCode(code).orElse(null);
                if (task != null) {
                    isUpdate = true;
                    task.getSubItems().clear();
                    task.getLogs().clear();
                } else {
                    task = new Task();
                }
            } else {
                task = new Task();
            }

            applyBatchItem(task, it);
            taskRepository.save(task);
            if (isUpdate) updated++;
            else imported++;
        }

        return new TaskDtos.ImportBatchResult(imported, updated, skipped, items.size(), errors);
    }

    /** 校验单条批量导入项，返回错误列表（空=通过）；validBoards 为 t_board 全部合法 code */
    private List<TaskDtos.ImportBatchError> validateBatchItem(TaskDtos.TaskBatchItem it, int rowIndex, Set<String> validBoards) {
        List<TaskDtos.ImportBatchError> errs = new ArrayList<>();
        if (it == null) {
            errs.add(new TaskDtos.ImportBatchError(rowIndex, "行", "条目为空", null));
            return errs;
        }
        String board = trimToNull(it.board());
        if (board == null) {
            errs.add(new TaskDtos.ImportBatchError(rowIndex, "看板", "看板必填（" + String.join(" / ", validBoards) + "）", it.board()));
        } else if (!validBoards.contains(board)) {
            errs.add(new TaskDtos.ImportBatchError(rowIndex, "看板", "看板值非法，应为 " + String.join(" / ", validBoards), board));
        }
        String title = it.title() == null ? null : it.title().trim();
        if (title == null || title.isEmpty()) {
            errs.add(new TaskDtos.ImportBatchError(rowIndex, "具体事项", "具体事项必填", it.title()));
        }
        String priority = trimToNull(it.priority());
        if (priority != null && !PRIORITIES.contains(priority)) {
            errs.add(new TaskDtos.ImportBatchError(rowIndex, "优先级", "优先级必须是 高 / 中 / 低", it.priority()));
        }
        String status = trimToNull(it.status());
        if (status != null && !STATUSES.contains(status)) {
            errs.add(new TaskDtos.ImportBatchError(rowIndex, "当前状态", "状态必须是 未启动 / 进行中 / 亟待解决 / 持续跟进 / 已完成", it.status()));
        }
        if (trimToNull(it.deadline()) != null && parseDate(it.deadline()) == null) {
            errs.add(new TaskDtos.ImportBatchError(rowIndex, "计划完成日期", "日期格式非法，应为 YYYY-MM-DD", it.deadline()));
        }
        if (trimToNull(it.updateDate()) != null && parseDate(it.updateDate()) == null) {
            errs.add(new TaskDtos.ImportBatchError(rowIndex, "更新日期", "日期格式非法，应为 YYYY-MM-DD", it.updateDate()));
        }
        if (it.logs() != null) {
            for (int i = 0; i < it.logs().size(); i++) {
                TaskDtos.TaskBatchLog l = it.logs().get(i);
                if (l == null) continue;
                if (trimToNull(l.date()) != null && parseDate(l.date()) == null) {
                    errs.add(new TaskDtos.ImportBatchError(rowIndex, "跟进记录#" + (i + 1) + ".日期", "日期格式非法，应为 YYYY-MM-DD", l.date()));
                }
            }
        }
        return errs;
    }

    /** 将校验通过的 TaskBatchItem 应用到 Task 实体（不处理 id） */
    private void applyBatchItem(Task task, TaskDtos.TaskBatchItem it) {
        task.setTaskCode(trimToNull(it.taskCode()));
        task.setBoard(trimToNull(it.board()));
        task.setModule(trimToNull(it.module()));
        task.setTitle(it.title().trim());
        task.setDescription(trimToNull(it.description()));
        task.setStatus(trimToNull(it.status()) == null ? "未启动" : it.status().trim());
        task.setPriority(trimToNull(it.priority()) == null ? "中" : it.priority().trim());
        task.setOwner(trimToNull(it.owner()));
        task.setCollab(trimToNull(it.collab()));
        task.setPain(trimToNull(it.pain()));
        task.setNextStep(trimToNull(it.nextStep()));
        task.setDeadline(parseDate(it.deadline()));
        task.setRisk(trimToNull(it.risk()));
        task.setUpdateDate(parseDate(it.updateDate()));
        if (task.getDeadline() != null) {
            task.setDeadlineMonth(task.getDeadline().toString().substring(0, 7));
        } else {
            task.setDeadlineMonth(null);
        }
        if (task.getNotifyStatus() == null) {
            task.setNotifyStatus("NONE");
        }

        if (it.subItems() != null) {
            int order = 0;
            for (String name : it.subItems()) {
                if (name == null || name.isBlank()) continue;
                SubItem si = new SubItem();
                si.setTask(task);
                si.setName(name.trim());
                si.setSortOrder(order++);
                task.getSubItems().add(si);
            }
        }
        if (it.logs() != null) {
            for (TaskDtos.TaskBatchLog l : it.logs()) {
                if (l == null || l.summary() == null || l.summary().isBlank()) continue;
                TaskLog tl = new TaskLog();
                tl.setTask(task);
                tl.setLogDate(parseDate(l.date()));
                tl.setPerson(trimToNull(l.person()));
                tl.setSummary(l.summary().trim());
                tl.setNextStep(trimToNull(l.next()));
                task.getLogs().add(tl);
            }
        }
    }

    private LocalDate parseDate(String s) {
        String v = trimToNull(s);
        if (v == null) {
            return null;
        }
        try {
            return LocalDate.parse(v);
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional
    public TaskDtos.TaskDetail transition(Long id, String status) {
        if (status == null || !STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "非法状态: " + status + "，可选: " + STATUSES);
        }
        Task task = requireTask(id);
        task.setStatus(status);
        task.setUpdateDate(LocalDate.now());
        taskRepository.save(task);
        weComApiClient.sendTaskNotify(task, "status");
        return toDetail(task);
    }

    @Transactional
    public TaskDtos.TaskDetail addLog(Long id, TaskDtos.TaskLogRequest req) {
        Task task = requireTask(id);
        if (req == null || req.summary() == null || req.summary().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "跟进摘要不能为空");
        }
        // 与 t_task_log 列长度一致，超长直接 400 而非数据库 500
        checkLen(req.summary(), 512, "跟进摘要");
        checkLen(req.person(), 64, "跟进人");
        checkLen(req.nextStep(), 512, "下一步");
        TaskLog log = new TaskLog();
        log.setTask(task);
        log.setLogDate(req.logDate() != null ? req.logDate() : LocalDate.now());
        log.setPerson(req.person());
        log.setSummary(req.summary());
        log.setNextStep(req.nextStep());
        task.getLogs().add(log);
        task.setUpdateDate(LocalDate.now());
        taskRepository.save(task);
        return toDetail(task);
    }

    private void checkLen(String s, int max, String field) {
        if (s != null && s.length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + "过长（最多 " + max + " 字）");
        }
    }

    /** 删除单条跟进记录（仅允许删除属于该事项的记录；经 orphanRemoval 级联删除）。 */
    @Transactional
    public void deleteLog(Long taskId, Long logId) {
        Task task = requireTask(taskId);
        TaskLog log = task.getLogs().stream()
                .filter(l -> l.getId().equals(logId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "跟进记录不存在"));
        task.getLogs().remove(log);
        taskRepository.save(task);
    }

    @Transactional
    public TaskDtos.TaskDetail notify(Long id, String scene) {
        Task task = requireTask(id);
        weComApiClient.sendTaskNotify(task, scene == null ? "manual" : scene);
        taskRepository.save(task);
        return toDetail(task);
    }

    @Transactional(readOnly = true)
    public List<TaskDtos.NotifyLogItem> notifyLogs(Long taskId) {
        return notifyLogRepository.findByTaskIdOrderByCreatedAtDesc(taskId)
                .stream().map(n -> new TaskDtos.NotifyLogItem(
                        n.getId(), n.getTaskId(), n.getTouser(), n.getContent(),
                        n.getResult(), n.getErrcode(), n.getErrmsg(), n.getCreatedAt()))
                .toList();
    }

    /**
     * 处理企微回调文本命令：`#事项ID 状态关键字`，返回给用户的回复文案。
     */
    @Transactional
    public String handleCallbackCommand(String userId, String content) {
        if (content == null || !content.trim().startsWith("#")) {
            return helpText();
        }
        String body = content.trim().substring(1).trim();
        int sp = body.indexOf(' ');
        if (sp <= 0) {
            return "格式：回复 #事项ID 状态（如 #QF-A01 完成）";
        }
        String code = body.substring(0, sp).trim();
        String keyword = body.substring(sp + 1).trim();
        String status = mapKeyword(keyword);
        if (status == null) {
            return "无法识别的状态：" + keyword + "\n" + helpText();
        }
        Task task = taskRepository.findByTaskCode(code).orElse(null);
        if (task == null) {
            return "未找到事项：" + code;
        }
        if (!task.getStatus().equals(status)) {
            task.setStatus(status);
            task.setUpdateDate(LocalDate.now());
            taskRepository.save(task);
            weComApiClient.sendTaskNotify(task, "status");
        }
        return "事项 #" + code + " " + task.getTitle() + " 已更新为【" + status + "】";
    }

    private String mapKeyword(String keyword) {
        return switch (keyword) {
            case "完成", "已完成", "完结" -> "已完成";
            case "开始", "进行中" -> "进行中";
            case "待办", "未启动" -> "未启动";
            case "解决", "亟待解决" -> "亟待解决";
            case "跟进", "持续跟进" -> "持续跟进";
            default -> null;
        };
    }

    private String helpText() {
        return "支持命令：回复 #事项ID 状态（如 #QF-A01 完成）\n"
                + "状态关键字：完成/开始/待办/解决/跟进";
    }

    private Task requireTask(Long id) {
        return taskRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "事项不存在: " + id));
    }

    private void applyRequest(Task task, TaskDtos.TaskRequest req) {
        if (req.board() != null && !req.board().isBlank()) {
            task.setBoard(req.board().trim());
        }
        task.setModule(req.module());
        task.setTitle(req.title() == null ? null : req.title().trim());
        task.setDescription(req.description());
        if (req.status() != null && !req.status().isBlank()) {
            task.setStatus(req.status().trim());
        }
        if (req.priority() != null && !req.priority().isBlank()) {
            task.setPriority(req.priority().trim());
        }
        task.setOwner(req.owner());
        task.setOwnerUserid(req.ownerUserid());
        task.setCollab(req.collab());
        task.setPain(req.pain());
        task.setNextStep(req.nextStep());
        task.setDeadline(req.deadline());
        // deadline_month 与 deadline 保持同步（导入路径同样维护该字段，编辑截止日期后不能留脏数据）
        if (req.deadline() != null) {
            task.setDeadlineMonth(req.deadline().toString().substring(0, 7));
        } else {
            task.setDeadlineMonth(null);
        }
        task.setRisk(req.risk());

        task.getSubItems().clear();
        if (req.subItems() != null) {
            int order = 0;
            for (String name : req.subItems()) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                SubItem si = new SubItem();
                si.setTask(task);
                si.setName(name.trim());
                si.setSortOrder(order++);
                task.getSubItems().add(si);
            }
        }

        // 跟进记录由独立接口（/logs）管理，更新请求通常不携带 logs；
        // 仅在显式传入时才整体重建，否则保留原有记录，避免编辑事项时误清空跟进记录。
        if (req.logs() != null) {
            task.getLogs().clear();
            for (TaskDtos.TaskLogRequest lr : req.logs()) {
                if (lr == null || lr.summary() == null || lr.summary().isBlank()) {
                    continue;
                }
                TaskLog tl = new TaskLog();
                tl.setTask(task);
                tl.setLogDate(lr.logDate() != null ? lr.logDate() : LocalDate.now());
                tl.setPerson(lr.person());
                tl.setSummary(lr.summary());
                tl.setNextStep(lr.nextStep());
                task.getLogs().add(tl);
            }
        }
    }

    private String normalize(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * 列表参数规整：去空白、去重；为空（null 或全部为空白）时返回 null，
     * 表示「不限制」。切勿返回空集合，以免 JPQL in () 在 MySQL 下报语法错误。
     */
    private List<String> normalizeList(List<String> list) {
        if (list == null) {
            return null;
        }
        List<String> r = list.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        return r.isEmpty() ? null : r;
    }

    /** 置顶/取消置顶：写 pinned + 刷新更新日期。 */
    @Transactional
    public TaskDtos.TaskDetail pin(Long id, boolean pinned) {
        Task task = requireTask(id);
        task.setPinned(pinned);
        task.setUpdateDate(LocalDate.now());
        taskRepository.save(task);
        return toDetail(task);
    }

    /**
     * 手动排序重排。
     * - 无锚点（afterId/beforeId 均 null）：旧语义——按传入 ids 顺序整体重写 sort_order = 数组下标（仅对传入 id 生效）。
     * - 有锚点：区间移动——ids 为被移动的连续块（新顺序），锚点定位目标位置后，对全部未置顶事项重排为稠密序号，
     *   保证 sort_order 与 id 升序兜底不冲突；未参与移动的事项相对顺序不变。
     */
    @Transactional
    public void reorder(TaskDtos.ReorderRequest req) {
        if (req == null || req.ids() == null || req.ids().isEmpty()) {
            return;
        }
        List<Long> ids = req.ids();
        if (req.afterId() == null && req.beforeId() == null) {
            Map<Long, Task> byId = new HashMap<>();
            for (Long id : ids) {
                if (id != null) {
                    byId.put(id, requireTask(id));
                }
            }
            for (int i = 0; i < ids.size(); i++) {
                Task t = byId.get(ids.get(i));
                // 置顶项不参与手动排序位置（排序查询 pinned 恒最前），跳过避免改写其 sort_order
                if (t != null && !t.isPinned()) {
                    t.setSortOrder(i);
                }
            }
            taskRepository.saveAll(byId.values());
            return;
        }

        List<Task> ordered = taskRepository.findAllNonPinned(Sort.by(
                new Sort.Order(Sort.Direction.ASC, "sortOrder", Sort.NullHandling.NULLS_LAST),
                new Sort.Order(Sort.Direction.ASC, "id")));
        Set<Long> moving = new HashSet<>(ids);
        List<Task> rest = ordered.stream().filter(t -> !moving.contains(t.getId())).toList();

        // 锚点定位：优先 afterId（目标位置上方邻居）；afterId 为置顶项（不在 rest）时块位于未置顶区顶部
        int idx;
        if (req.afterId() != null) {
            int at = indexOfId(rest, req.afterId());
            idx = at < 0 ? 0 : at + 1;
        } else {
            int bt = indexOfId(rest, req.beforeId());
            idx = bt < 0 ? rest.size() : bt;
        }

        Map<Long, Task> blockById = new HashMap<>();
        for (Long id : ids) {
            if (id != null) {
                blockById.put(id, requireTask(id));
            }
        }
        // 置顶项不在 rest（findAllNonPinned）中，若混入 block 会被塞进未置顶区间并改写 sort_order，
        // 这里一并剔除，保持置顶项不参与手动排序位置
        List<Task> block = ids.stream()
                .map(blockById::get)
                .filter(Objects::nonNull)
                .filter(t -> !t.isPinned())
                .toList();
        List<Task> result = new ArrayList<>(rest);
        result.addAll(idx, block);
        for (int i = 0; i < result.size(); i++) {
            result.get(i).setSortOrder(i);
        }
        taskRepository.saveAll(result);
    }

    private int indexOfId(List<Task> list, Long id) {
        if (id == null) {
            return -1;
        }
        for (int i = 0; i < list.size(); i++) {
            if (id.equals(list.get(i).getId())) {
                return i;
            }
        }
        return -1;
    }

    private TaskDtos.TaskListItem toListItem(Task t, Long logCount, List<String> subItems) {
        return new TaskDtos.TaskListItem(
                t.getId(), t.getTaskCode(), t.getBoard(), t.getModule(), t.getTitle(),
                t.getDescription(), t.getStatus(), t.getPriority(), t.getOwner(), t.getOwnerUserid(),
                t.getCollab(), t.getPain(), t.getNextStep(), t.getDeadline(), t.getRisk(),
                t.isPinned(), t.getSortOrder(),
                t.getNotifyStatus(), t.getUpdateDate(), t.getUpdatedAt(), subItems, logCount);
    }

    private TaskDtos.TaskDetail toDetail(Task t) {
        List<String> subItems = t.getSubItems().stream().map(SubItem::getName).toList();
        List<TaskDtos.TaskLogItem> logs = t.getLogs().stream()
                .map(l -> new TaskDtos.TaskLogItem(l.getId(), l.getLogDate(), l.getPerson(),
                        l.getSummary(), l.getNextStep()))
                .toList();
        return new TaskDtos.TaskDetail(
                t.getId(), t.getTaskCode(), t.getBoard(), t.getModule(), t.getTitle(),
                t.getDescription(), t.getStatus(), t.getPriority(), t.getOwner(), t.getOwnerUserid(),
                t.getCollab(), t.getPain(), t.getNextStep(), t.getDeadline(), t.getRisk(),
                t.isPinned(), t.getSortOrder(),
                t.getNotifyStatus(), t.getUpdateDate(), subItems, logs);
    }
}
