-- =====================================================================
-- 智能客服/前台助手模块 建表脚本（独立于核心表，可重复执行）
-- 执行：mysql -uroot -p hotel < chat_schema.sql
-- =====================================================================

-- =====================================================
-- 1. 客服会话表
-- =====================================================
CREATE TABLE IF NOT EXISTS `chat_session` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '会话ID',
  `session_no`      VARCHAR(32)  NOT NULL                COMMENT '会话编号(业务唯一)',
  `user_id`         BIGINT       NOT NULL                COMMENT '发起用户ID(关联user.id)',
  `hotel_id`        BIGINT       DEFAULT NULL            COMMENT '酒店ID(关联hotel.id,NULL=全局咨询)',
  `source`          TINYINT      NOT NULL DEFAULT 0      COMMENT '会话来源:0-用户端 1-前台助手(酒店侧)',
  `status`          TINYINT      NOT NULL DEFAULT 0      COMMENT '状态:0-进行中 1-已结束 2-已转人工 3-超时回收',
  `transfer_flag`   TINYINT      NOT NULL DEFAULT 0      COMMENT '转人工标记:0-否 1-已转人工',
  `transfer_reason` VARCHAR(255) DEFAULT NULL            COMMENT '转人工原因(出范围/用户要求/低置信度)',
  `tag`             VARCHAR(20)  DEFAULT NULL            COMMENT '会话标签:已解决/转技术/投诉(客服标记)',
  `staff_user_id`   BIGINT       DEFAULT NULL            COMMENT '接手的客服/前台用户ID',
  `start_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '开始时间',
  `end_time`        DATETIME     DEFAULT NULL            COMMENT '结束时间',
  `message_count`   INT          NOT NULL DEFAULT 0      COMMENT '消息总数',
  `rating`          TINYINT      DEFAULT NULL            COMMENT '满意度评价:1-5星',
  `rating_comment`  VARCHAR(255) DEFAULT NULL            COMMENT '评价内容',
  `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_no` (`session_no`),
  KEY `idx_user` (`user_id`),
  KEY `idx_hotel_status` (`hotel_id`,`status`),
  KEY `idx_create_time` (`create_time`)   -- 对话趋势按日期分组统计
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能客服会话表';

-- =====================================================
-- 2. 对话消息表
-- =====================================================
CREATE TABLE IF NOT EXISTS `chat_message` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '消息ID',
  `session_id`  BIGINT      NOT NULL                COMMENT '会话ID(关联chat_session.id)',
  `sender`      TINYINT     NOT NULL                COMMENT '发送者:0-用户 1-AI助手 2-人工客服/前台',
  `content`     TEXT        NOT NULL                COMMENT '消息内容',
  `msg_type`    TINYINT     NOT NULL DEFAULT 0      COMMENT '类型:0-文本 1-订单卡片 2-工单卡片 3-富文本/链接',
  `intent_tag`  VARCHAR(50) DEFAULT NULL            COMMENT '意图标签(预订/查房态/服务请求/投诉/闲聊等)',
  `entities`    JSON        DEFAULT NULL            COMMENT '抽取实体(JSON:日期/房型/数量等)',
  `tool_name`   VARCHAR(50) DEFAULT NULL            COMMENT '触发的工具名(如有)',
  `token_count` INT         DEFAULT NULL            COMMENT 'Token消耗(统计/计费)',
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
  PRIMARY KEY (`id`),
  KEY `idx_session` (`session_id`),
  KEY `idx_intent` (`intent_tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='客服对话消息表';

-- =====================================================
-- 3. 知识库表
-- =====================================================
CREATE TABLE IF NOT EXISTS `knowledge_base` (
  `id`                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '知识ID',
  `hotel_id`             BIGINT       DEFAULT NULL            COMMENT '所属酒店ID(NULL=平台公共知识)',
  `question`             VARCHAR(500) NOT NULL                COMMENT '标准问题',
  `answer`               TEXT         NOT NULL                COMMENT '标准答案',
  `category`             VARCHAR(50)  DEFAULT NULL            COMMENT '分类:酒店信息/服务设施/周边推荐/政策',
  `similarity_threshold` DECIMAL(4,3) NOT NULL DEFAULT 0.700  COMMENT '召回阈值(低于该值不采纳,防幻觉)',
  `vector_id`            VARCHAR(64)  DEFAULT NULL            COMMENT '向量ID(向量库中的文档ID)',
  `status`               TINYINT      NOT NULL DEFAULT 1      COMMENT '状态:0-停用 1-启用',
  `hit_count`            INT          NOT NULL DEFAULT 0      COMMENT '命中次数(热问统计)',
  `create_time`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_hotel_category` (`hotel_id`,`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='客服知识库表';

-- =====================================================
-- 4. 服务工单表（送水/六小件/清洁等，流转到前台处理）
-- =====================================================
CREATE TABLE IF NOT EXISTS `service_ticket` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '工单ID',
  `ticket_no`     VARCHAR(32)  NOT NULL                COMMENT '工单编号(业务唯一)',
  `session_id`    BIGINT       NOT NULL                COMMENT '来源会话ID(关联chat_session.id)',
  `user_id`       BIGINT       NOT NULL                COMMENT '发起用户ID(关联user.id)',
  `hotel_id`      BIGINT       NOT NULL                COMMENT '酒店ID(关联hotel.id)',
  `room_id`       BIGINT       DEFAULT NULL            COMMENT '关联房间ID(送物类工单)',
  `request_type`  VARCHAR(30)  NOT NULL                COMMENT '请求类型:送水/送六小件/清洁提醒/其他',
  `content`       VARCHAR(500) DEFAULT NULL            COMMENT '需求描述',
  `priority`      TINYINT      NOT NULL DEFAULT 1      COMMENT '优先级:1-普通 2-紧急',
  `status`        TINYINT      NOT NULL DEFAULT 0      COMMENT '状态:0-待处理 1-处理中 2-已完成 3-已取消',
  `assignee_id`   BIGINT       DEFAULT NULL            COMMENT '处理人(前台用户ID,role=3)',
  `handle_result` VARCHAR(500) DEFAULT NULL            COMMENT '处理结果',
  `handled_time`  DATETIME     DEFAULT NULL            COMMENT '处理完成时间',
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ticket_no` (`ticket_no`),
  KEY `idx_hotel_status` (`hotel_id`,`status`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='服务工单表';
