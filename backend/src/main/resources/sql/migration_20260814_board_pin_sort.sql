-- ============================================
-- 迁移：看板表 t_board + 事项置顶(pinned) / 手动排序(sort_order)
-- 适用于已按旧版 init.sql 建库的环境；重复执行安全（IF NOT EXISTS / ON DUPLICATE KEY）
-- 注意：第 3 步「临时事项归入临时专项看板」依赖真实环境中的模块名，示例数据可能与线上不同，
--       执行后请核对：SELECT id, task_code, board, module FROM t_task WHERE board='temp';
-- ============================================
SET NAMES utf8mb4;
USE tmo_task;

-- 1) 看板表（支持未来动态新增看板）
CREATE TABLE IF NOT EXISTS t_board (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  code        VARCHAR(32)  NOT NULL COMMENT '看板代码(唯一，如 quanfa/happy/temp)',
  name        VARCHAR(64)  NOT NULL COMMENT '看板名称(如 全发/会幸福/临时专项)',
  accent      VARCHAR(16)  NOT NULL DEFAULT '#2B59C3' COMMENT '身份色(十六进制)',
  prefix      VARCHAR(8)   NOT NULL DEFAULT 'QF' COMMENT '事项ID前缀(如 QF/HF/LS，1-2位大写字母)',
  sort_order  INT          NOT NULL DEFAULT 0 COMMENT '侧栏显示排序',
  system_flag TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '系统看板(1=禁止删除)',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  UNIQUE KEY uk_board_code   (code),
  UNIQUE KEY uk_board_prefix (prefix)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='看板表';

-- 2) 看板种子数据（3 个系统看板；重复执行时仅刷新名称/配色，不覆盖排序与系统标记）
INSERT INTO t_board (code, name, accent, prefix, sort_order, system_flag) VALUES
('quanfa','全发',   '#2B59C3','QF',0,1),
('happy','会幸福',  '#E8862C','HF',1,1),
('temp', '临时专项','#8B5CF6','LS',2,1)
ON DUPLICATE KEY UPDATE name=VALUES(name), accent=VALUES(accent);

-- 3) t_task 新增置顶 / 手动排序字段（迁移期间锁表风险低，48 条数据规模可直接执行）
ALTER TABLE t_task ADD COLUMN pinned     TINYINT(1) NOT NULL DEFAULT 0 COMMENT '置顶(1=置顶，任意排序下排最前)' AFTER risk;
ALTER TABLE t_task ADD COLUMN sort_order INT DEFAULT NULL COMMENT '手动排序位置(仅手动排序模式生效)' AFTER pinned;
CREATE INDEX idx_task_pinned ON t_task (pinned);

-- 4) 数据迁移：模块名含「临时」的事项归入临时专项看板（示例：现库 3 条 QF-H01~03）
UPDATE t_task SET board='temp' WHERE module LIKE '%临时%';
