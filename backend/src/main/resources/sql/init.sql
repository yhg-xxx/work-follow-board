-- ============================================
-- 企业微信团队待办管理应用 建库建表脚本
-- 数据库：tmo_task  字符集：utf8mb4  (MySQL 8+)
-- ============================================
CREATE DATABASE IF NOT EXISTS tmo_task DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE tmo_task;

-- 事项/待办主表（字段对齐参考看板 JSON）
CREATE TABLE IF NOT EXISTS t_task (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  task_code     VARCHAR(32)   DEFAULT NULL COMMENT '事项ID(如QF-A01)，用于企微命令识别',
  board         VARCHAR(32)   NOT NULL DEFAULT 'quanfa' COMMENT '看板分组(quanfa全发/happy会幸福)',
  module        VARCHAR(64)   DEFAULT NULL COMMENT '工作模块',
  title         VARCHAR(255)  NOT NULL COMMENT '具体事项',
  description   TEXT          DEFAULT NULL COMMENT '详细描述(可选)',
  status        VARCHAR(16)   NOT NULL DEFAULT '未启动' COMMENT '当前状态(未启动/进行中/亟待解决/持续跟进/已完成)',
  priority      VARCHAR(8)    NOT NULL DEFAULT '中' COMMENT '优先级(高/中/低)',
  owner         VARCHAR(64)   DEFAULT NULL COMMENT '负责人(组/人名)',
  owner_userid  VARCHAR(64)   DEFAULT NULL COMMENT '负责人企微userid(用于推送)',
  collab        VARCHAR(255)  DEFAULT NULL COMMENT '协作方·对接人',
  pain          VARCHAR(512)  DEFAULT NULL COMMENT '亟待解决问题·痛点',
  next_step     VARCHAR(512)  DEFAULT NULL COMMENT '下一步行动',
  deadline      DATE          DEFAULT NULL COMMENT '计划完成日期',
  risk          VARCHAR(512)  DEFAULT NULL COMMENT '风险提示·备注',
  notify_status VARCHAR(16)   NOT NULL DEFAULT 'NONE' COMMENT '企微通知状态(NONE/SENT/FAILED)',
  update_date   DATE          DEFAULT NULL COMMENT '更新日期',
  deadline_month VARCHAR(7)   DEFAULT NULL COMMENT '计划完成月份(如2026-08，原样取自看板JSON)',
  created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  KEY idx_task_status   (status),
  KEY idx_task_owner    (owner),
  KEY idx_task_deadline (deadline),
  UNIQUE KEY uk_task_code (task_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事项/待办主表';

-- 子项表（JSON subItems）
CREATE TABLE IF NOT EXISTS t_sub_item (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  task_id    BIGINT       NOT NULL COMMENT '所属事项ID',
  name       VARCHAR(128) NOT NULL COMMENT '子项名称',
  sort_order INT          NOT NULL DEFAULT 0 COMMENT '排序',
  KEY idx_sub_task (task_id),
  CONSTRAINT fk_sub_task FOREIGN KEY (task_id) REFERENCES t_task(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='子项表';

-- 跟进记录表（JSON logs）
CREATE TABLE IF NOT EXISTS t_task_log (
  id        BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  task_id   BIGINT       NOT NULL COMMENT '所属事项ID',
  log_date  DATE         DEFAULT NULL COMMENT '跟进日期',
  person    VARCHAR(64)  DEFAULT NULL COMMENT '跟进人',
  summary   VARCHAR(512) DEFAULT NULL COMMENT '跟进摘要',
  next_step VARCHAR(512) DEFAULT NULL COMMENT '下一步',
  KEY idx_log_task (task_id),
  CONSTRAINT fk_log_task FOREIGN KEY (task_id) REFERENCES t_task(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跟进记录表';

-- 企微推送记录表
CREATE TABLE IF NOT EXISTS t_notify_log (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  task_id    BIGINT       DEFAULT NULL COMMENT '关联事项ID',
  touser     VARCHAR(64)  NOT NULL COMMENT '企微接收人userid',
  content    TEXT         NOT NULL COMMENT '推送内容',
  result     VARCHAR(16)  NOT NULL COMMENT '结果(SENT/FAILED)',
  errcode    INT          DEFAULT NULL COMMENT '企微返回错误码',
  errmsg     VARCHAR(255) DEFAULT NULL COMMENT '企微返回错误信息',
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '推送时间',
  KEY idx_notify_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='企微推送记录表';

-- 前端操作日志表（记录关键写操作：新建/编辑/删除/状态/导入/导出/跟进）
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
