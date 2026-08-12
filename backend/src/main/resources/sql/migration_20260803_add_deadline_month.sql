-- ============================================
-- 迁移：t_task 增加 deadline_month 列（看板 JSON 的 deadlineMonth 原样入库）
-- 适用于已按旧版 init.sql 建库的环境，仅需执行一次
-- MySQL 不支持 ADD COLUMN IF NOT EXISTS，重复执行会报错（Duplicate column），请勿重复执行
-- ============================================
SET NAMES utf8mb4;
USE tmo_task;

ALTER TABLE t_task
  ADD COLUMN deadline_month VARCHAR(7) DEFAULT NULL COMMENT '计划完成月份(如2026-08，原样取自看板JSON)' AFTER update_date;
