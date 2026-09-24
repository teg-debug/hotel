-- =====================================================================
-- 迁移脚本 v2：库存模型重构 + 支付流水业务类型 + 扫描索引
-- =====================================================================
-- 适用场景：已导入旧版 schema.sql 且带有业务数据的环境。
--          此类环境不能用 schema.sql 重建（它会先 DROP 再 CREATE，会清空数据）。
-- 执行方式：mysql -uroot -p hotel < migration_v2_inventory_and_payment.sql
--          或在客户端中执行 source 命令。
-- 注意：本脚本不是幂等脚本。各步骤在 MySQL 8 中没有 IF NOT EXISTS 写法，
--      重复执行会报「列/索引已存在」，属预期，不会破坏数据。
-- =====================================================================

USE hotel;

-- ---------------------------------------------------------------------
-- 1. 房间状态语义变更
--    旧：0-空闲 1-已订 2-打扫中
--    新：0-空闲 1-停用维修 2-打扫中
--    「是否已被预订」改为完全由 booking_order 的日期区间决定，
--    因此历史上由系统在下单时写入的 1（已订）必须重置为空闲，
--    否则这些房间会被新语义解释成「停用维修」而永久不可售。
-- ---------------------------------------------------------------------
UPDATE room SET status = 0 WHERE status = 1;

-- ---------------------------------------------------------------------
-- 2. 支付流水新增业务类型：1-支付 2-退款
--    退款会以独立流水行记录，因此需要区分一行代表收款还是退款。
-- ---------------------------------------------------------------------
ALTER TABLE payment_log
  ADD COLUMN biz_type TINYINT NOT NULL DEFAULT 1 COMMENT '业务类型:1-支付 2-退款' AFTER pay_no;

-- ---------------------------------------------------------------------
-- 3. 房型表的会员折扣字段作废
--    折扣率改由配置 app.member.discount 按会员等级决定，
--    该列从未被代码读取，保留会与配置形成两套口径。
--    下单时的折扣快照仍在 booking_order.member_discount 中保留。
-- ---------------------------------------------------------------------
ALTER TABLE room_type DROP COLUMN member_discount;

-- ---------------------------------------------------------------------
-- 4. 超时关单扫描索引
--    OrderExpireTask 按 status + expire_time 筛选，原先无可用索引会全表扫描。
-- ---------------------------------------------------------------------
ALTER TABLE booking_order ADD INDEX idx_status_expire (status, expire_time);

-- ---------------------------------------------------------------------
-- 5. 对话趋势统计索引
--    趋势接口按 DATE_FORMAT(create_time) 分组，无索引会全表扫描。
-- ---------------------------------------------------------------------
ALTER TABLE chat_session ADD INDEX idx_create_time (create_time);

-- ---------------------------------------------------------------------
-- 6. 自检
-- ---------------------------------------------------------------------
SELECT 'room.status 分布' AS chk;
SELECT status, COUNT(*) AS cnt FROM room GROUP BY status;

SELECT 'room_type 是否已移除 member_discount' AS chk;
SELECT COUNT(*) AS remaining FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'hotel' AND TABLE_NAME = 'room_type' AND COLUMN_NAME = 'member_discount';

SELECT 'payment_log 是否已有 biz_type' AS chk;
SELECT COUNT(*) AS has_col FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'hotel' AND TABLE_NAME = 'payment_log' AND COLUMN_NAME = 'biz_type';

SELECT '索引检查（应为 2 行）' AS chk;
SELECT TABLE_NAME, INDEX_NAME FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'hotel'
  AND INDEX_NAME IN ('idx_status_expire', 'idx_create_time')
GROUP BY TABLE_NAME, INDEX_NAME;
