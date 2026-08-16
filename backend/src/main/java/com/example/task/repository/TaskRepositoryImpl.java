package com.example.task.repository;

import com.example.task.entity.Task;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link TaskSearchRepository} 实现：EntityManager 动态拼 JPQL。
 * 排序规格：pinned 恒最前（desc），随后按排序模式的单/双键 + id 兜底；
 * 游标 keyset 谓词按列递归生成，正确处理 nulls-last（NULL 恒排在非空值之后）。
 */
public class TaskRepositoryImpl implements TaskSearchRepository {

    /** 置顶列：转成 int 参与 < / = 比较（JPQL 不支持 boolean 的 <）。 */
    private static final String PIN = "case when t.pinned = true then 1 else 0 end";
    /** 优先级排序权重：高=3 / 中=2 / 低=1 / 其他=0。 */
    private static final String PRI_RANK =
            "case t.priority when '高' then 3 when '中' then 2 when '低' then 1 else 0 end";

    /** 排序键：列表达式 + 升序标志 + 是否可空（参与 nulls-last 处理）+ 游标取值来源。 */
    private record Key(String expr, boolean asc, boolean nullable, boolean fromPinned, boolean fromK1, boolean fromK2, boolean fromId) {
    }

    private final EntityManager em;

    public TaskRepositoryImpl(EntityManager em) {
        this.em = em;
    }

    /** 各排序模式的键序列（最后一列恒为 t.id，非空，用于游标严格推进）。 */
    private static List<Key> keysFor(String mode) {
        return switch (mode == null ? "asc" : mode) {
            case "manual" -> List.of(
                    new Key(PIN, false, false, true, false, false, false),
                    new Key("t.sortOrder", true, true, false, true, false, false),
                    new Key("t.id", true, false, false, false, false, true));
            case "desc" -> List.of(
                    new Key(PIN, false, false, true, false, false, false),
                    new Key("t.deadline", false, true, false, true, false, false),
                    new Key("t.id", true, false, false, false, false, true));
            case "priority" -> List.of(
                    new Key(PIN, false, false, true, false, false, false),
                    new Key(PRI_RANK, false, false, false, true, false, false),
                    new Key("t.deadline", true, true, false, false, true, false),
                    new Key("t.id", true, false, false, false, false, true));
            case "updateDate" -> List.of(
                    new Key(PIN, false, false, true, false, false, false),
                    new Key("t.updateDate", false, true, false, true, false, false),
                    new Key("t.deadline", true, true, false, false, true, false),
                    new Key("t.id", true, false, false, false, false, true));
            default -> List.of(
                    new Key(PIN, false, false, true, false, false, false),
                    new Key("t.deadline", true, true, false, true, false, false),
                    new Key("t.id", true, false, false, false, false, true));
        };
    }

    private static Object cursorValue(Key k, SearchCursor c) {
        if (k.fromPinned()) return c.pinned() == null ? null : (c.pinned() ? 1 : 0);
        if (k.fromK1()) return c.k1();
        if (k.fromK2()) return c.k2();
        return c.id();
    }

    private static String orderBy(String mode) {
        List<Key> ks = keysFor(mode);
        StringBuilder sb = new StringBuilder("order by ");
        for (int i = 0; i < ks.size(); i++) {
            if (i > 0) sb.append(", ");
            Key k = ks.get(i);
            sb.append(k.expr()).append(k.asc() ? " asc" : " desc");
            if (k.nullable()) sb.append(" nulls last");
        }
        return sb.toString();
    }

    /**
     * 递归生成 keyset 谓词（含参数占位）：取排序序列中“游标所在行之后”的行。
     * 可空列在游标值为 null 时（nulls-last 的 NULL 段）只按后续列继续推进。
     */
    private void appendKeyset(StringBuilder ql, List<Object> params, List<Key> ks, SearchCursor c, int idx) {
        if (idx >= ks.size()) {
            return;
        }
        Key k = ks.get(idx);
        Object v = cursorValue(k, c);
        if (k.fromId()) {
            // 最后一列（id）：严格大于游标 id
            int p = params.size() + 1;
            params.add(v);
            ql.append("t.id > ?").append(p);
            return;
        }
        if (k.nullable() && v == null) {
            ql.append("(").append(k.expr()).append(" is null and ");
            appendKeyset(ql, params, ks, c, idx + 1);
            ql.append(")");
            return;
        }
        int p = params.size() + 1;
        params.add(v);
        ql.append("(").append(k.expr()).append(" ").append(k.asc() ? ">" : "<").append(" ?").append(p);
        if (k.nullable()) {
            // nulls-last：NULL 恒排在所有非空值之后
            ql.append(" or ").append(k.expr()).append(" is null");
        }
        ql.append(" or (").append(k.expr()).append(" = ?").append(p).append(" and ");
        appendKeyset(ql, params, ks, c, idx + 1);
        ql.append("))");
    }

    /** 收集公共筛选条件（全部可选，null/空集合表示不限制）。 */
    private List<String> filterConds(List<Object> params,
                                     List<String> boards, List<String> statuses, List<String> modules,
                                     List<String> owners, String keyword,
                                     LocalDate deadlineFrom, LocalDate deadlineTo) {
        List<String> conds = new ArrayList<>();
        addIn(conds, params, "t.board", boards);
        addIn(conds, params, "t.status", statuses);
        addIn(conds, params, "t.module", modules);
        addIn(conds, params, "t.owner", owners);
        String kw = keyword == null ? null : keyword.trim();
        if (kw != null && !kw.isEmpty()) {
            int p = params.size() + 1;
            conds.add("(t.title like ?" + p + " or t.taskCode like ?" + p
                    + " or t.module like ?" + p + " or t.pain like ?" + p + ")");
            params.add("%" + kw + "%");
        }
        if (deadlineFrom != null) {
            addSingle(conds, params, "t.deadline >= ?", deadlineFrom);
        }
        if (deadlineTo != null) {
            addSingle(conds, params, "t.deadline <= ?", deadlineTo);
        }
        return conds;
    }

    private void addIn(List<String> conds, List<Object> params, String col, List<String> values) {
        if (values != null && !values.isEmpty()) {
            int p = params.size() + 1;
            conds.add(col + " in ?" + p);
            params.add(values);
        }
    }

    private void addSingle(List<String> conds, List<Object> params, String prefix, Object value) {
        int p = params.size() + 1;
        conds.add(prefix + p);
        params.add(value);
    }

    private void bind(TypedQuery<?> q, List<Object> params) {
        for (int i = 0; i < params.size(); i++) {
            q.setParameter(i + 1, params.get(i));
        }
    }

    @Override
    public List<Task> searchPage(String sortMode,
                                 List<String> boards, List<String> statuses, List<String> modules,
                                 List<String> owners, String keyword,
                                 LocalDate deadlineFrom, LocalDate deadlineTo,
                                 SearchCursor cursor, int limit) {
        List<Object> params = new ArrayList<>();
        StringBuilder ql = new StringBuilder("select t from Task t");
        List<String> conds = filterConds(params, boards, statuses, modules, owners, keyword, deadlineFrom, deadlineTo);
        if (!conds.isEmpty()) {
            ql.append(" where ").append(String.join(" and ", conds));
        }
        if (cursor != null) {
            ql.append(conds.isEmpty() ? " where " : " and ");
            appendKeyset(ql, params, keysFor(sortMode), cursor, 0);
        }
        ql.append(" ").append(orderBy(sortMode));
        TypedQuery<Task> q = em.createQuery(ql.toString(), Task.class);
        bind(q, params);
        q.setMaxResults(limit);
        return q.getResultList();
    }

    @Override
    public long countTotal(List<String> boards, List<String> statuses, List<String> modules,
                           List<String> owners, String keyword,
                           LocalDate deadlineFrom, LocalDate deadlineTo) {
        List<Object> params = new ArrayList<>();
        StringBuilder ql = new StringBuilder("select count(t) from Task t");
        List<String> conds = filterConds(params, boards, statuses, modules, owners, keyword, deadlineFrom, deadlineTo);
        if (!conds.isEmpty()) {
            ql.append(" where ").append(String.join(" and ", conds));
        }
        TypedQuery<Long> q = em.createQuery(ql.toString(), Long.class);
        bind(q, params);
        return q.getSingleResult();
    }

    @Override
    public Object[] countStats(List<String> boards, List<String> statuses, List<String> modules,
                               List<String> owners, String keyword,
                               LocalDate deadlineFrom, LocalDate deadlineTo,
                               LocalDate today, LocalDate todayPlus7) {
        List<Object> params = new ArrayList<>();
        List<String> conds = filterConds(params, boards, statuses, modules, owners, keyword, deadlineFrom, deadlineTo);
        // 统计段的 near 区间参数编号在 filter 参数之后
        int t1 = params.size() + 1;
        int t2 = params.size() + 2;
        params.add(today);
        params.add(todayPlus7);
        StringBuilder ql = new StringBuilder("""
                select count(t),
                       sum(case when t.status = '亟待解决' then 1 else 0 end),
                       sum(case when t.status = '进行中' then 1 else 0 end),
                       sum(case when t.priority = '高' then 1 else 0 end),
                       sum(case when t.deadline is not null and t.deadline >= ?%d and t.deadline <= ?%d then 1 else 0 end)
                from Task t
                """.formatted(t1, t2));
        if (!conds.isEmpty()) {
            ql.append(" where ").append(String.join(" and ", conds));
        }
        TypedQuery<Object[]> q = em.createQuery(ql.toString(), Object[].class);
        bind(q, params);
        return q.getSingleResult();
    }
}
