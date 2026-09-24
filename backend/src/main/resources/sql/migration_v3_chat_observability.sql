-- =====================================================================
-- 迁移脚本 v3：客服模块统计与可观测性索引
-- =====================================================================
-- 适用场景：已导入 chat_schema.sql 的环境（有业务数据），不能重建表。
-- 执行方式：mysql -uroot -p hotel < migration_v3_chat_observability.sql
-- 注意：本脚本不是幂等脚本，重复执行会报「索引已存在」，属预期，不会破坏数据。
-- =====================================================================

USE hotel;

-- ---------------------------------------------------------------------
-- 1. 热问榜统计索引
--    热问榜改为统计用户真实提问后，按 sender + create_time 过滤并分组，
--    chat_message 原先只有 idx_session 与 idx_intent，时间窗口过滤会全表扫描。
-- ---------------------------------------------------------------------
ALTER TABLE chat_message ADD INDEX idx_sender_time (sender, create_time);

-- ---------------------------------------------------------------------
-- 2. 会话超时回收索引
--    回收任务按 status 筛选未结束会话，再取最后消息时间。
--    chat_session 现有 idx_hotel_status 以 hotel_id 为前导列，单独按 status 查用不上，
--    这里补一个以 status 为前导列的索引。
-- ---------------------------------------------------------------------
ALTER TABLE chat_session ADD INDEX idx_status_start (status, start_time);

-- ---------------------------------------------------------------------
-- 3. 自检
-- ---------------------------------------------------------------------
SELECT '索引检查（应为 2 行）' AS chk;
SELECT TABLE_NAME, INDEX_NAME FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'hotel'
  AND INDEX_NAME IN ('idx_sender_time', 'idx_status_start')
GROUP BY TABLE_NAME, INDEX_NAME;

SELECT 'tool_name / token_count 写入情况（本次功能上线前应为 0）' AS chk;
SELECT COUNT(*) AS with_tool_name FROM chat_message WHERE tool_name IS NOT NULL;
SELECT COUNT(*) AS with_token_count FROM chat_message WHERE token_count IS NOT NULL;
