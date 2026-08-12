#!/usr/bin/env node
/**
 * 看板 JSON → MySQL 导入 SQL 生成器（一次性导入，原封不动保留字段）
 *
 * 用法：node tools/gen-import-sql.mjs [json路径]
 *   - json路径 缺省为 TMO 根目录下 工作跟进看板_2026-08-03.json
 *   - 输出：backend/src/main/resources/sql/data_2026-08-03.sql（文件名跟随输入文件）
 *
 * 规则：
 *   - 主表 t_task 用 INSERT IGNORE，靠 uk_task_code 去重，可重复执行
 *   - 子项/跟进记录以 @tid（按 task_code 查 id）关联，无唯一键，请勿重复执行
 *   - 字符串原样保留（含 '—'、空串）；缺失/空串的日期字段写 NULL
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join, basename, extname } from 'node:path'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const jsonPath = process.argv[2]
  ? (process.argv[2].startsWith('\\') || process.argv[2].startsWith('/') || /^[A-Za-z]:/.test(process.argv[2])
      ? process.argv[2]
      : join(process.cwd(), process.argv[2]))
  : join(root, '工作跟进看板_2026-08-03.json')

const doc = JSON.parse(readFileSync(jsonPath, 'utf8'))
const data = doc.data
const boards = ['quanfa', 'happy']
const outName = 'data_' + basename(jsonPath).replace(extname(jsonPath), '').replace(/^工作跟进看板_/, '') + '.sql'
const outPath = join(root, 'backend', 'src', 'main', 'resources', 'sql', outName)

/** SQL 字符串转义（先反斜杠后单引号，MySQL 默认把 \ 当转义符） */
const esc = (s) => String(s).replace(/\\/g, '\\\\').replace(/'/g, "''")

/** 普通字符串：缺失 → NULL；其余原样（含空串） */
const str = (v) => (v === undefined || v === null ? 'NULL' : `'${esc(v)}'`)
/** 日期/月份：缺失或空串 → NULL */
const date = (v) => (v === undefined || v === null || v === '' ? 'NULL' : `'${esc(v)}'`)

const lines = []
let taskCount = 0
let subCount = 0
let logCount = 0

const header = `-- ============================================
-- 一次性数据导入脚本（由 tools/gen-import-sql.mjs 自动生成，请勿手改）
-- 数据来源：${basename(jsonPath)}
-- 执行方式：
--   mysql -u root -p tmo_task < ${outName}
--   或在 MySQL 客户端中：source ${outName}
-- 注意：主表 t_task 按 task_code 去重（INSERT IGNORE），重复执行安全；
--       子项 t_sub_item / 跟进记录 t_task_log 无唯一键，重复执行会重复插入，
--       请勿重复执行。
-- ============================================
SET NAMES utf8mb4;
USE tmo_task;
`
lines.push(header)

const boardLabel = { quanfa: '全发', happy: '会幸福' }
for (const board of boards) {
  const items = data[board] || []
  lines.push(`\n-- ============ 看板：${board}（${boardLabel[board]}） ============`)
  for (const it of items) {
    taskCount++
    const id = it.id
    const title = it.item ?? ''
    lines.push(`\n-- [${id}] ${title}`)
    lines.push(`INSERT IGNORE INTO t_task`)
    lines.push(`  (task_code, board, module, title, status, priority, owner, collab,`)
    lines.push(`   pain, next_step, deadline, risk, update_date, deadline_month, notify_status)`)
    lines.push(`VALUES`)
    lines.push(`  (${str(id)},'${board}',${str(it.module)},${str(it.item)},${str(it.status)},${str(it.priority)},${str(it.owner)},${str(it.collab)},`)
    lines.push(`   ${str(it.pain)},${str(it.next)},${date(it.deadline)},${str(it.risk)},${date(it.updateDate)},${date(it.deadlineMonth)},'NONE');`)
    lines.push(``)
    lines.push(`SET @tid := (SELECT id FROM t_task WHERE task_code = ${str(id)});`)

    if (Array.isArray(it.subItems) && it.subItems.length) {
      lines.push(`\n-- 子项`)
      it.subItems.forEach((name, i) => {
        subCount++
        lines.push(`INSERT INTO t_sub_item (task_id, name, sort_order) VALUES (@tid, ${str(name)}, ${i});`)
      })
    }

    if (Array.isArray(it.logs) && it.logs.length) {
      lines.push(`\n-- 跟进记录`)
      for (const lg of it.logs) {
        logCount++
        lines.push(`INSERT INTO t_task_log (task_id, log_date, person, summary, next_step)`)
        lines.push(`VALUES (@tid, ${date(lg.date)}, ${str(lg.person)}, ${str(lg.summary)}, ${str(lg.next)});`)
      }
    }
  }
}

lines.push(`\n-- 导入完成（预计：主表 ${taskCount} 条、子项 ${subCount} 条、跟进记录 ${logCount} 条）`)
lines.push(`-- 校验：SELECT COUNT(*) FROM t_task; 应为 ${taskCount}`)

mkdirSync(dirname(outPath), { recursive: true })
writeFileSync(outPath, lines.join('\n') + '\n', 'utf8')

console.log(`已生成：${outPath}`)
console.log(`主表 ${taskCount} 条（quanfa ${(data.quanfa || []).length} / happy ${(data.happy || []).length}）`)
console.log(`子项 ${subCount} 条，跟进记录 ${logCount} 条`)
