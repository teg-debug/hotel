package com.hotel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付流水表：一次支付或退款对应一行。
 *
 * <p>{@code payNo} 上仍有唯一键，因此同一支付流水号先失败后成功时，
 * 处理逻辑是更新原行而不是插入新行，否则会被唯一键拦下。</p>
 */
@Data
@TableName("payment_log")
public class PaymentLog {

    /** 业务类型：支付 */
    public static final int BIZ_PAY = 1;
    /** 业务类型：退款 */
    public static final int BIZ_REFUND = 2;

    /** 状态：处理中 */
    public static final int STATUS_PENDING = 0;
    /** 状态：成功（支付成功或退款成功） */
    public static final int STATUS_SUCCESS = 1;
    /** 状态：失败 */
    public static final int STATUS_FAILED = 2;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private String orderNo;
    /** 支付流水号或退款流水号（全局唯一，幂等依据） */
    private String payNo;
    /** 业务类型：1-支付 2-退款 */
    private Integer bizType;
    /** 支付方式：0-模拟支付 1-微信 2-支付宝 */
    private Integer payType;
    private BigDecimal amount;
    /** 状态：0-处理中 1-成功 2-失败 */
    private Integer status;
    private LocalDateTime callbackTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
