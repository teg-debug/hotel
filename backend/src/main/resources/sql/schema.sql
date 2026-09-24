-- =====================================================================
-- 酒店预订管理系统 - 全量初始化脚本（建表 DDL + 初始数据）
-- =====================================================================
-- 用法：连接 MySQL 后整体执行本文件即可（会自动创建 hotel 库并建表导数据）
--   方式一：mysql -uroot -p < schema.sql
--   方式二：Navicat / DataGrid 等工具直接运行本文件
--
-- 种子账号（密码统一为：password）：
--   admin     角色=2 管理员      可查看全部酒店订单与统计
--   owner1    角色=1 经营者      名下：上海外滩云顶酒店、上海静安宜家快捷酒店
--   owner2    角色=1 经营者      名下：北京王府井豪庭大酒店、杭州西湖悦享酒店
--   testuser  角色=0 普通用户    银卡会员（享受 0.95 折）
--
-- 可重复导入：脚本会先 DROP 再 CREATE，重复执行会清空旧数据
-- =====================================================================

-- 0. 创建并选择数据库
CREATE DATABASE IF NOT EXISTS hotel DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE hotel;

-- 清理旧表（无外键约束，顺序任意；按逻辑从子表到父表）
DROP TABLE IF EXISTS booking_order;
DROP TABLE IF EXISTS payment_log;
DROP TABLE IF EXISTS room;
DROP TABLE IF EXISTS room_type;
DROP TABLE IF EXISTS hotel;
DROP TABLE IF EXISTS `user`;

-- =====================================================================
-- 1. 建表 DDL
-- =====================================================================

-- -----------------------------------------------------
-- 用户表
-- -----------------------------------------------------
CREATE TABLE `user` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username`      VARCHAR(50)  NOT NULL                COMMENT '用户名(登录账号)',
  `password`      VARCHAR(100) NOT NULL                COMMENT '密码(BCrypt加密,不存明文)',
  `nickname`      VARCHAR(50)  DEFAULT NULL            COMMENT '昵称',
  `phone`         VARCHAR(20)  DEFAULT NULL            COMMENT '手机号',
  `email`         VARCHAR(100) DEFAULT NULL            COMMENT '邮箱',
  `avatar`        VARCHAR(255) DEFAULT NULL            COMMENT '头像URL',
  `member_level`  TINYINT      NOT NULL DEFAULT 0      COMMENT '会员等级:0-普通 1-银卡 2-金卡',
  `role`          TINYINT      NOT NULL DEFAULT 0      COMMENT '角色:0-普通用户 1-酒店经营者 2-管理员 3-酒店前台',
  `hotel_id`      BIGINT       DEFAULT NULL            COMMENT '前台账号绑定酒店ID(role=3时有效)',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '状态:0-禁用 1-正常',
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  KEY `idx_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- -----------------------------------------------------
-- 酒店表
-- -----------------------------------------------------
CREATE TABLE `hotel` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '酒店ID',
  `owner_id`      BIGINT       NOT NULL                COMMENT '经营者用户ID(关联user.id,后台数据隔离依据)',
  `name`          VARCHAR(100) NOT NULL                COMMENT '酒店名称',
  `city`          VARCHAR(50)  NOT NULL                COMMENT '所在城市(搜索条件)',
  `address`       VARCHAR(255) NOT NULL                COMMENT '详细地址',
  `star_level`    TINYINT      NOT NULL DEFAULT 3      COMMENT '星级:1-5星',
  `description`   TEXT                                 COMMENT '酒店简介',
  `cover_img`     VARCHAR(255) DEFAULT NULL            COMMENT '封面图URL',
  `images`        TEXT                                 COMMENT '酒店图片(逗号分隔URL,支持多张放大查看)',
  `latitude`      DECIMAL(10,7) DEFAULT NULL           COMMENT '纬度(地理位置)',
  `longitude`     DECIMAL(10,7) DEFAULT NULL           COMMENT '经度(地理位置)',
  `phone`         VARCHAR(20)  DEFAULT NULL            COMMENT '联系电话',
  `checkin_time`  VARCHAR(20)  DEFAULT '14:00'         COMMENT '默认入住时间',
  `checkout_time` VARCHAR(20)  DEFAULT '12:00'         COMMENT '默认退房时间',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '状态:0-下架 1-营业中',
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_city` (`city`),
  KEY `idx_owner` (`owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='酒店表';

-- -----------------------------------------------------
-- 房型表
-- -----------------------------------------------------
CREATE TABLE `room_type` (
  `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '房型ID',
  `hotel_id`    BIGINT        NOT NULL                COMMENT '所属酒店ID(关联hotel.id)',
  `name`        VARCHAR(50)   NOT NULL                COMMENT '房型名称:大床房/双床房/套房',
  `bed_type`    VARCHAR(20)   DEFAULT NULL            COMMENT '床型:大床/双床',
  `area`        INT           DEFAULT NULL            COMMENT '面积(平方米)',
  `max_guests`  INT           NOT NULL DEFAULT 2      COMMENT '最多入住人数',
  `price`       DECIMAL(10,2) NOT NULL                COMMENT '门市价(元/晚)',
  `breakfast`   TINYINT       NOT NULL DEFAULT 0      COMMENT '是否含早餐:0-否 1-是',
  `img_url`     VARCHAR(255)  DEFAULT NULL            COMMENT '房型图片URL',
  `status`      TINYINT       NOT NULL DEFAULT 1      COMMENT '状态:0-停售 1-在售',
  `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_hotel` (`hotel_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='房型表';

-- -----------------------------------------------------
-- 房间表（具体房间，预订的原子单位）
-- status 只表示物理/运营状态，不代表库存占用：
-- 「某天是否已被预订」由 booking_order 的日期区间决定
-- -----------------------------------------------------
CREATE TABLE `room` (
  `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '房间ID',
  `hotel_id`      BIGINT      NOT NULL                COMMENT '所属酒店ID(冗余,便于按酒店查)',
  `room_type_id`  BIGINT      NOT NULL                COMMENT '所属房型ID(关联room_type.id)',
  `room_no`       VARCHAR(20) NOT NULL                COMMENT '房间号(如801)',
  `floor`         INT         DEFAULT NULL            COMMENT '所在楼层',
  `status`        TINYINT     NOT NULL DEFAULT 0      COMMENT '状态:0-空闲 1-停用维修 2-打扫中',
  `create_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_hotel_room_no` (`hotel_id`,`room_no`),
  KEY `idx_room_type` (`room_type_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='房间表';

-- -----------------------------------------------------
-- 预订订单表（订单状态机 + 日期区间防冲突）
-- -----------------------------------------------------
CREATE TABLE `booking_order` (
  `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '订单ID',
  `order_no`        VARCHAR(32)   NOT NULL                COMMENT '订单编号(业务唯一)',
  `user_id`         BIGINT        NOT NULL                COMMENT '下单用户ID(关联user.id)',
  `hotel_id`        BIGINT        NOT NULL                COMMENT '酒店ID(冗余快照)',
  `hotel_name`      VARCHAR(100)  DEFAULT NULL            COMMENT '酒店名称快照',
  `room_type_id`    BIGINT        NOT NULL                COMMENT '房型ID(冗余快照)',
  `room_type_name`  VARCHAR(50)   DEFAULT NULL            COMMENT '房型名称快照',
  `room_id`         BIGINT        NOT NULL                COMMENT '锁定房间ID(关联room.id,防超卖核心)',
  `room_no`         VARCHAR(20)   DEFAULT NULL            COMMENT '房间号快照',
  `checkin_date`    DATE          NOT NULL                COMMENT '入住日期',
  `checkout_date`   DATE          NOT NULL                COMMENT '离店日期',
  `night_count`     INT           NOT NULL                COMMENT '入住晚数(checkout-checkin)',
  `guest_name`      VARCHAR(50)   NOT NULL                COMMENT '入住人姓名',
  `guest_phone`     VARCHAR(20)   NOT NULL                COMMENT '入住人电话',
  `room_price`      DECIMAL(10,2) NOT NULL                COMMENT '成交房单价(含会员折扣后)',
  `member_discount` DECIMAL(3,2)  NOT NULL DEFAULT 1.00   COMMENT '下单时会员折扣率快照',
  `total_amount`    DECIMAL(10,2) NOT NULL                COMMENT '订单总额=单价*晚数',
  `status`          TINYINT       NOT NULL DEFAULT 0      COMMENT '订单状态:0-待支付 1-已确认 2-已入住 3-已取消 4-已完成',
  `cancel_reason`   VARCHAR(255)  DEFAULT NULL            COMMENT '取消原因(超时/用户取消)',
  `remark`          VARCHAR(255)  DEFAULT NULL            COMMENT '订单备注',
  `pay_time`        DATETIME      DEFAULT NULL            COMMENT '支付时间',
  `expire_time`     DATETIME      DEFAULT NULL            COMMENT '过期时间(超时未支付自动取消)',
  `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_user` (`user_id`),
  KEY `idx_hotel_status` (`hotel_id`,`status`),
  KEY `idx_room_date` (`room_id`,`checkin_date`,`checkout_date`),  -- 防冲突查询索引
  KEY `idx_status_expire` (`status`,`expire_time`)                 -- 超时关单扫描索引
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='预订订单表';

-- -----------------------------------------------------
-- 支付流水表（一次支付或退款对应一行）
-- -----------------------------------------------------
CREATE TABLE `payment_log` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '流水ID',
  `order_id`      BIGINT        NOT NULL                COMMENT '订单ID(关联booking_order.id)',
  `order_no`      VARCHAR(32)   NOT NULL                COMMENT '订单编号(冗余)',
  `pay_no`        VARCHAR(64)   NOT NULL                COMMENT '支付或退款流水号(全局唯一,幂等依据)',
  `biz_type`      TINYINT       NOT NULL DEFAULT 1      COMMENT '业务类型:1-支付 2-退款',
  `pay_type`      TINYINT       NOT NULL DEFAULT 0      COMMENT '支付方式:0-模拟支付 1-微信 2-支付宝',
  `amount`        DECIMAL(10,2) NOT NULL                COMMENT '金额(元)',
  `status`        TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0-处理中 1-成功 2-失败',
  `callback_time` DATETIME      DEFAULT NULL            COMMENT '回调时间',
  `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pay_no` (`pay_no`),
  KEY `idx_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='支付流水表';

-- =====================================================================
-- 2. 初始数据
-- =====================================================================

-- -----------------------------------------------------
-- 用户（密码统一 password，BCrypt 哈希为 Spring 官方文档示例值）
-- -----------------------------------------------------
INSERT INTO `user` (id, username, password, nickname, phone, member_level, role, status, create_time, update_time) VALUES
(1, 'admin',    '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', '系统管理员',   '13800000001', 0, 2, 1, NOW(), NOW()),
(2, 'owner1',   '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', '云顶酒店经营者', '13800000002', 0, 1, 1, NOW(), NOW()),
(3, 'owner2',   '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', '豪庭酒店经营者', '13800000003', 0, 1, 1, NOW(), NOW()),
(4, 'testuser', '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', '测试用户',     '13800000004', 1, 0, 1, NOW(), NOW());

-- -----------------------------------------------------
-- 酒店（owner_id 关联 user.id；图片/坐标供详情放大查看与地图展示）
-- -----------------------------------------------------
INSERT INTO `hotel` (id, owner_id, name, city, address, star_level, description, cover_img, images, latitude, longitude, phone, checkin_time, checkout_time, status, create_time, update_time) VALUES
(1, 2, '上海外滩云顶酒店', '上海', '黄浦区中山东一路 88 号', 4,
 '位于外滩核心地段，江景房可远眺陆家嘴天际线',
 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=luxury%20hotel%20exterior%20Shanghai%20Bund%20riverside%20modern%20architecture%20dusk&image_size=landscape_16_9',
 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=luxury%20hotel%20lobby%20interior%20chandelier&image_size=landscape_16_9,https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=hotel%20room%20floor%20to%20ceiling%20window%20Shanghai%20skyline%20night&image_size=landscape_16_9',
 31.2402000, 121.4900000, '021-88880001', '14:00', '12:00', 1, NOW(), NOW()),
(2, 2, '上海静安宜家快捷酒店', '上海', '静安区南京西路 1200 号', 3,
 '经济型连锁酒店，交通便利，性价比高',
 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=budget%20business%20hotel%20exterior%20city%20street%20Shanghai&image_size=landscape_16_9',
 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=economy%20hotel%20reception%20desk%20clean&image_size=landscape_16_9',
 31.2265000, 121.4596000, '021-88880002', '14:00', '12:00', 1, NOW(), NOW()),
(3, 3, '北京王府井豪庭大酒店', '北京', '东城区王府井大街 120 号', 5,
 '五星级豪华酒店，近故宫与王府井商圈',
 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=grand%20five%20star%20hotel%20exterior%20Beijing%20Wangfujing%20street&image_size=landscape_16_9',
 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=luxury%20hotel%20lobby%20chinese%20style%20interior&image_size=landscape_16_9,https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=presidential%20suite%20living%20room%20luxury&image_size=landscape_16_9',
 39.9148000, 116.4107000, '010-66660001', '14:00', '12:00', 1, NOW(), NOW()),
(4, 3, '杭州西湖悦享酒店', '杭州', '西湖区北山街 66 号', 4,
 '紧邻西湖景区，步行可达断桥残雪',
 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=boutique%20hotel%20near%20West%20Lake%20Hangzhou%20garden%20style&image_size=landscape_16_9',
 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=hotel%20balcony%20view%20West%20Lake%20scenery&image_size=landscape_16_9',
 30.2496000, 120.1494000, '0571-55550001', '14:00', '12:00', 1, NOW(), NOW());

-- -----------------------------------------------------
-- 房型（price 为门市价；会员折扣由 app.member.discount 配置按等级决定）
-- -----------------------------------------------------
INSERT INTO `room_type` (id, hotel_id, name, bed_type, area, max_guests, price, breakfast, img_url, status, create_time, update_time) VALUES
-- 上海外滩云顶酒店
(1,  1, '高级大床房', '大床', 35,  2,  680.00, 1,
 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=hotel%20king%20bed%20room%20modern%20style&image_size=landscape_16_9', 1, NOW(), NOW()),
(2,  1, '豪华双床房', '双床', 40,  2,  780.00, 1,
 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=hotel%20twin%20bed%20room%20deluxe&image_size=landscape_16_9', 1, NOW(), NOW()),
(3,  1, '江景套房',   '大床', 68,  3, 1580.00, 1,
 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=hotel%20suite%20living%20room%20river%20view&image_size=landscape_16_9', 1, NOW(), NOW()),
-- 上海静安宜家快捷酒店
(4,  2, '标准大床房', '大床', 22,  2,  320.00, 0, '', 1, NOW(), NOW()),
(5,  2, '标准双床房', '双床', 26,  2,  380.00, 0, '', 1, NOW(), NOW()),
-- 北京王府井豪庭大酒店
(6,  3, '行政大床房', '大床', 45,  2, 1280.00, 1,
 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=executive%20king%20room%20luxury%20hotel&image_size=landscape_16_9', 1, NOW(), NOW()),
(7,  3, '行政双床房', '双床', 48,  2, 1380.00, 1, '', 1, NOW(), NOW()),
(8,  3, '总统套房',   '大床', 120, 4, 2880.00, 1, '', 1, NOW(), NOW()),
-- 杭州西湖悦享酒店
(9,  4, '湖景大床房', '大床', 38,  2,  520.00, 1,
 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=hotel%20room%20view%20West%20Lake%20Hangzhou&image_size=landscape_16_9', 1, NOW(), NOW()),
(10, 4, '湖景双床房', '双床', 42,  2,  580.00, 1, '', 1, NOW(), NOW()),
(11, 4, '雅致套房',   '大床', 70,  3, 1080.00, 1, '', 1, NOW(), NOW());

-- -----------------------------------------------------
-- 房间（status: 0-空闲 1-停用维修 2-打扫中；房间号 = 楼层 + 编号）
-- -----------------------------------------------------
-- 上海外滩云顶酒店：高级大床房 801-808 / 豪华双床房 601-608 / 江景套房 901-904
INSERT INTO `room` (hotel_id, room_type_id, room_no, floor, status, create_time, update_time) VALUES
(1, 1, '801', 8, 0, NOW(), NOW()), (1, 1, '802', 8, 0, NOW(), NOW()),
(1, 1, '803', 8, 0, NOW(), NOW()), (1, 1, '804', 8, 0, NOW(), NOW()),
(1, 1, '805', 8, 0, NOW(), NOW()), (1, 1, '806', 8, 0, NOW(), NOW()),
(1, 1, '807', 8, 0, NOW(), NOW()), (1, 1, '808', 8, 0, NOW(), NOW()),
(1, 2, '601', 6, 0, NOW(), NOW()), (1, 2, '602', 6, 0, NOW(), NOW()),
(1, 2, '603', 6, 0, NOW(), NOW()), (1, 2, '604', 6, 0, NOW(), NOW()),
(1, 2, '605', 6, 0, NOW(), NOW()), (1, 2, '606', 6, 0, NOW(), NOW()),
(1, 2, '607', 6, 0, NOW(), NOW()), (1, 2, '608', 6, 0, NOW(), NOW()),
(1, 3, '901', 9, 0, NOW(), NOW()), (1, 3, '902', 9, 0, NOW(), NOW()),
(1, 3, '903', 9, 0, NOW(), NOW()), (1, 3, '904', 9, 0, NOW(), NOW());

-- 上海静安宜家快捷酒店：标准大床房 301-306 / 标准双床房 201-206（203 为打扫中）
INSERT INTO `room` (hotel_id, room_type_id, room_no, floor, status, create_time, update_time) VALUES
(2, 4, '301', 3, 0, NOW(), NOW()), (2, 4, '302', 3, 0, NOW(), NOW()),
(2, 4, '303', 3, 0, NOW(), NOW()), (2, 4, '304', 3, 0, NOW(), NOW()),
(2, 4, '305', 3, 0, NOW(), NOW()), (2, 4, '306', 3, 0, NOW(), NOW()),
(2, 5, '201', 2, 0, NOW(), NOW()), (2, 5, '202', 2, 0, NOW(), NOW()),
(2, 5, '203', 2, 2, NOW(), NOW()), (2, 5, '204', 2, 0, NOW(), NOW()),
(2, 5, '205', 2, 0, NOW(), NOW()), (2, 5, '206', 2, 0, NOW(), NOW());

-- 北京王府井豪庭大酒店：行政大床房 801-810 / 行政双床房 601-608 / 总统套房 1201-1206
INSERT INTO `room` (hotel_id, room_type_id, room_no, floor, status, create_time, update_time) VALUES
(3, 6, '801', 8, 0, NOW(), NOW()), (3, 6, '802', 8, 0, NOW(), NOW()),
(3, 6, '803', 8, 0, NOW(), NOW()), (3, 6, '804', 8, 0, NOW(), NOW()),
(3, 6, '805', 8, 0, NOW(), NOW()), (3, 6, '806', 8, 0, NOW(), NOW()),
(3, 6, '807', 8, 0, NOW(), NOW()), (3, 6, '808', 8, 0, NOW(), NOW()),
(3, 6, '809', 8, 0, NOW(), NOW()), (3, 6, '810', 8, 0, NOW(), NOW()),
(3, 7, '601', 6, 0, NOW(), NOW()), (3, 7, '602', 6, 0, NOW(), NOW()),
(3, 7, '603', 6, 0, NOW(), NOW()), (3, 7, '604', 6, 0, NOW(), NOW()),
(3, 7, '605', 6, 0, NOW(), NOW()), (3, 7, '606', 6, 0, NOW(), NOW()),
(3, 7, '607', 6, 0, NOW(), NOW()), (3, 7, '608', 6, 0, NOW(), NOW()),
(3, 8, '1201', 12, 0, NOW(), NOW()), (3, 8, '1202', 12, 0, NOW(), NOW()),
(3, 8, '1203', 12, 0, NOW(), NOW()), (3, 8, '1204', 12, 0, NOW(), NOW()),
(3, 8, '1205', 12, 0, NOW(), NOW()), (3, 8, '1206', 12, 0, NOW(), NOW());

-- 杭州西湖悦享酒店：湖景大床房 501-506 / 湖景双床房 401-406 / 雅致套房 701-703
INSERT INTO `room` (hotel_id, room_type_id, room_no, floor, status, create_time, update_time) VALUES
(4, 9,  '501', 5, 0, NOW(), NOW()), (4, 9,  '502', 5, 0, NOW(), NOW()),
(4, 9,  '503', 5, 0, NOW(), NOW()), (4, 9,  '504', 5, 0, NOW(), NOW()),
(4, 9,  '505', 5, 0, NOW(), NOW()), (4, 9,  '506', 5, 0, NOW(), NOW()),
(4, 10, '401', 4, 0, NOW(), NOW()), (4, 10, '402', 4, 0, NOW(), NOW()),
(4, 10, '403', 4, 0, NOW(), NOW()), (4, 10, '404', 4, 0, NOW(), NOW()),
(4, 10, '405', 4, 0, NOW(), NOW()), (4, 10, '406', 4, 0, NOW(), NOW()),
(4, 11, '701', 7, 0, NOW(), NOW()), (4, 11, '702', 7, 0, NOW(), NOW()),
(4, 11, '703', 7, 0, NOW(), NOW());

-- =====================================================================
-- 导入后自检（可选执行）
-- SELECT (SELECT COUNT(*) FROM `user`)       AS users,
--        (SELECT COUNT(*) FROM hotel)        AS hotels,
--        (SELECT COUNT(*) FROM room_type)    AS room_types,
--        (SELECT COUNT(*) FROM room)         AS rooms;
-- 期望：users=4 / hotels=4 / room_types=11 / rooms=71
-- =====================================================================
