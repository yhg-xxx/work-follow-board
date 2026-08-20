-- ============================================
-- 迁移：t_task 补看板/模块索引
-- 列表筛选、侧栏菜单统计、nextCode 均按 board 查询/分组，此前 board 无索引
-- 适用于已按旧版 init.sql 建库的环境；新库的这两个索引已包含在 init.sql 中
-- 注意：仅执行一次（重复执行报 Duplicate key name）
-- ============================================
SET NAMES utf8mb4;
USE tmo_task;

CREATE INDEX idx_task_board ON t_task (board);
CREATE INDEX idx_task_board_module ON t_task (board, module);
