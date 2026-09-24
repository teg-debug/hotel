package com.hotel.dto;

import lombok.Data;

/**
 * 转人工请求参数
 */
@Data
public class TransferDTO {

    /** 转人工原因（可选） */
    private String reason;
}
