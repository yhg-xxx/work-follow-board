-- ============================================
-- 迁移：新增前端操作日志表 t_op_log
-- 适用于已按旧版 init.sql 建库的环境；重复执行安全（IF NOT EXISTS）
-- ============================================
SET NAMES utf8mb4;
USE tmo_task;

CREATE TABLE IF NOT EXISTS t_op_log (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  action      VARCHAR(32)  NOT NULL COMMENT '操作类型(CREATE/UPDATE/DELETE/STATUS/IMPORT/EXPORT/LOG_ADD/LOG_DELETE)',
  target_type VARCHAR(16)  DEFAULT NULL COMMENT '对象类型(task)',
  target_id   BIGINT       DEFAULT NULL COMMENT '对象ID',
  target_code VARCHAR(32)  DEFAULT NULL COMMENT '事项ID(如QF-A01)，便于展示',
  detail      VARCHAR(512) DEFAULT NULL COMMENT '操作描述(前端拼好的中文文案)',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  KEY idx_oplog_created (created_at),
  KEY idx_oplog_action (action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='前端操作日志表';
