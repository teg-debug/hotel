package com.hotel.vo;

import com.hotel.entity.PaymentLog;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付流水 VO：一次支付或退款对应一条记录
 */
@Data
public class PaymentLogVO {

    private String payNo;
    /** 业务类型：1-支付 2-退款 */
    private Integer bizType;
    /** 支付方式：0-模拟支付 1-微信 2-支付宝 */
    private Integer payType;
    private BigDecimal amount;
    /** 状态：0-处理中 1-成功 2-失败 */
    private Integer status;
    private LocalDateTime createTime;

    public static PaymentLogVO from(PaymentLog log) {
        PaymentLogVO vo = new PaymentLogVO();
        vo.setPayNo(log.getPayNo());
        vo.setBizType(log.getBizType());
        vo.setPayType(log.getPayType());
        vo.setAmount(log.getAmount());
        vo.setStatus(log.getStatus());
        vo.setCreateTime(log.getCreateTime());
        return vo;
    }
}
