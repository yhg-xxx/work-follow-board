package com.example.task.service;

import com.example.task.dto.TaskDtos;
import com.example.task.dto.MenuStatsDtos;
import com.example.task.entity.SubItem;
import com.example.task.entity.Task;
import com.example.task.entity.TaskLog;
import com.example.task.repository.NotifyLogRepository;
import com.example.task.repository.TaskRepository;
import com.example.wecom.WeComApiClient;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    /** 临时事项聚合关键字（与前端保持一致）。 */
    private static final String TEMP_MODULE_HINT = "临时";
    /** 事项ID 规律：{看板前缀}-{模块字母}{序号}，如 QF-B03 / HF-C02。 */
    private static final Pattern ID_RE = Pattern.compile("^(QF|HF)-([A-Z])(\\d+)$");

    private final TaskRepository taskRepository;
    private final NotifyLogRepository notifyLogRepository;
    private final WeComApiClient weComApiClient;

    public TaskService(TaskRepository taskRepository,
                       NotifyLogRepository notifyLogRepository,
                       WeComApiClient weComApiClient) {
        this.taskRepository = taskRepository;
        this.notifyLogRepository = notifyLogRepository;
        this.weComApiClient = weComApiClient;
    }

    @Transactional(readOnly = true)
    public List<TaskDtos.TaskListItem> list(List<String> boards, List<String> statuses, List<String> modules,
                                            List<String> owners, String keyword, String deadlineFrom, String deadlineTo,
                                            String sortOrder) {
        List<Task> tasks = taskRepository.search(
                normalizeList(boards), normalizeList(statuses), normalizeList(modules),
                normalizeList(owners), parseDate(deadlineFrom), parseDate(deadlineTo), normalize(keyword),
                buildDeadlineSort(sortOrder));
        // 一次聚合查询拿到各事项的跟进条数，供卡片角标展示
        Map<Long, Long> logCounts = taskRepository.countLogsByTask().stream()
                .collect(Collectors.toMap(r -> (Long) r[0], r -> (Long) r[1]));
        // 一次查询拿到全部子项，按事项 id 分组（保持排序号顺序）
        Map<Long, List<String>> subItemsByTask = taskRepository.findSubItemNamesByTask().stream()
                .collect(Collectors.groupingBy(r -> (Long) r[0],
                        LinkedHashMap::new,
                        Collectors.mapping(r -> (String) r[1], Collectors.toList())));
        return tasks.stream()
                .map(t -> toListItem(t,
                        logCounts.getOrDefault(t.getId(), 0L),
                        subItemsByTask.getOrDefault(t.getId(), List.of())))
                .toList();
    }

    /**
     * 构造按 deadline 排序的 Sort：null（无截止日期）始终排最后。
     * sortOrder 取值 asc/desc，默认 asc。
     */
    private Sort buildDeadlineSort(String sortOrder) {
        Sort.Direction dir = "desc".equalsIgnoreCase(sortOrder) ? Sort.Direction.DESC : Sort.Direction.ASC;
        return Sort.by(new Sort.Order(dir, "deadline", Sort.NullHandling.NULLS_LAST));
    }

    /**
     * 侧边栏菜单聚合统计：全部数量 + 各看板分组（含模块列表）+ 临时事项聚合。
     * 临时模块从各看板模块列表中剔除（已单独聚合为 temp）；空 module 不计入模块子列表，但计入分组总数。
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

        Map<String, Long> quanfa = byBoard.getOrDefault("quanfa", new LinkedHashMap<>());
        Map<String, Long> happy = byBoard.getOrDefault("happy", new LinkedHashMap<>());

        // 临时事项：跨看板聚合模块名含「临时」的计数
        Map<String, Long> tempMap = new LinkedHashMap<>();
        for (Map<String, Long> mm : byBoard.values()) {
            for (Map.Entry<String, Long> e : mm.entrySet()) {
                if (e.getKey() != null && e.getKey().contains(TEMP_MODULE_HINT)) {
                    tempMap.merge(e.getKey(), e.getValue(), Long::sum);
                }
            }
        }

        List<MenuStatsDtos.GroupStat> groups = List.of(
                new MenuStatsDtos.GroupStat("quanfa", sumValues(quanfa), buildModuleStats(quanfa, true)),
                new MenuStatsDtos.GroupStat("happy", sumValues(happy), buildModuleStats(happy, true)));
        MenuStatsDtos.GroupStat temp = new MenuStatsDtos.GroupStat("temp", sumValues(tempMap), buildModuleStats(tempMap, false));
        return new MenuStatsDtos.MenuStats(allCount, groups, temp);
    }

    /** 负责人去重列表（模糊匹配）。 */
    @Transactional(readOnly = true)
    public List<String> owners(String q) {
        return taskRepository.findOwners(normalize(q));
    }

    /** 指定看板下的工作模块去重列表（模糊匹配）。 */
    @Transactional(readOnly = true)
    public List<String> modules(String board, String q) {
        String b = normalize(board);
        return taskRepository.findModulesByBoard(b == null ? "quanfa" : b, normalize(q));
    }

    /**
     * 生成下一个事项ID：{前缀}-{字母}{序号}。
     * 已有模块复用其字母；新模块顺延分配下一个未使用字母；序号取该字母现有最大值 +1。
     */
    @Transactional(readOnly = true)
    public String nextCode(String board, String module) {
        String b = normalize(board);
        if (b == null) {
            b = "quanfa";
        }
        String prefix = "happy".equals(b) ? "HF" : "QF";

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

    /** 把 模块->计数 映射构造为已排序的模块统计列表（按数量降序）。excludeTemp 为 true 时剔除临时模块。 */
    private List<MenuStatsDtos.ModuleStat> buildModuleStats(Map<String, Long> modMap, boolean excludeTemp) {
        List<MenuStatsDtos.ModuleStat> list = new ArrayList<>();
        for (Map.Entry<String, Long> e : modMap.entrySet()) {
            String name = e.getKey();
            if (name == null || name.isBlank()) {
                continue;
            }
            if (excludeTemp && name.contains(TEMP_MODULE_HINT)) {
                continue;
            }
            list.add(new MenuStatsDtos.ModuleStat(name, e.getValue()));
        }
        list.sort((a, b) -> Long.compare(b.count(), a.count()));
        return list;
    }

    @Transactional(readOnly = true)
    public TaskDtos.TaskDetail detail(Long id) {
        return toDetail(requireTask(id));
    }

    @Transactional
    public TaskDtos.TaskDetail create(TaskDtos.TaskRequest req) {
        if (req.title() == null || req.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标题不能为空");
        }
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

    private static final Set<String> BOARDS = Set.of("quanfa", "happy");

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

        List<TaskDtos.ImportBatchError> errors = new ArrayList<>();
        int imported = 0;
        int updated = 0;
        int skipped = 0;

        if ("overwrite".equals(mode)) {
            notifyLogRepository.deleteAllInBatch();
            taskRepository.deleteAllInBatch();
        }

        List<TaskDtos.TaskBatchItem> items = req.items();
        for (int i = 0; i < items.size(); i++) {
            TaskDtos.TaskBatchItem it = items.get(i);
            List<TaskDtos.ImportBatchError> rowErrs = validateBatchItem(it, i);
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

    /** 校验单条批量导入项，返回错误列表（空=通过） */
    private List<TaskDtos.ImportBatchError> validateBatchItem(TaskDtos.TaskBatchItem it, int rowIndex) {
        List<TaskDtos.ImportBatchError> errs = new ArrayList<>();
        if (it == null) {
            errs.add(new TaskDtos.ImportBatchError(rowIndex, "行", "条目为空", null));
            return errs;
        }
        String board = trimToNull(it.board());
        if (board == null) {
            errs.add(new TaskDtos.ImportBatchError(rowIndex, "看板", "看板必填（quanfa 或 happy）", it.board()));
        } else if (!BOARDS.contains(board)) {
            errs.add(new TaskDtos.ImportBatchError(rowIndex, "看板", "看板值非法，应为 quanfa 或 happy", board));
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
        task.setTitle(req.title());
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

        task.getLogs().clear();
        if (req.logs() != null) {
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

    private TaskDtos.TaskListItem toListItem(Task t, Long logCount, List<String> subItems) {
        return new TaskDtos.TaskListItem(
                t.getId(), t.getTaskCode(), t.getBoard(), t.getModule(), t.getTitle(),
                t.getDescription(), t.getStatus(), t.getPriority(), t.getOwner(), t.getOwnerUserid(),
                t.getCollab(), t.getPain(), t.getNextStep(), t.getDeadline(), t.getRisk(),
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
                t.getNotifyStatus(), t.getUpdateDate(), subItems, logs);
    }
}
