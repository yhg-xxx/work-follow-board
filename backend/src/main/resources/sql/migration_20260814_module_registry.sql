-- ============================================
-- migration_20260814_module_registry.sql
-- 工作模块注册表：支持「新建工作模块」先存在（0 事项），侧栏模块列表 = 注册表 ∪ 任务实际出现的模块名。
-- 可重入（IF NOT EXISTS + 唯一键）。执行后请核对：
--   SHOW CREATE TABLE t_module;
--   SELECT * FROM t_module;  -- 初始应为空（既有模块仍从 t_task.module 文本聚合）
-- ============================================
CREATE TABLE IF NOT EXISTS t_module (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  board      VARCHAR(32) NOT NULL COMMENT '看板code（见 t_board.code）',
  name       VARCHAR(64) NOT NULL COMMENT '工作模块名',
  sort_order INT         NOT NULL DEFAULT 0 COMMENT '侧栏展示排序（注册表模块）',
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  UNIQUE KEY uk_module_board_name (board, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作模块注册表';
