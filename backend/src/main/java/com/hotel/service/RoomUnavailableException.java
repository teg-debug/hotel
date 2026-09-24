package com.hotel.service;

import com.hotel.common.BusinessException;

/**
 * 内部信号：某个候选房间在下单瞬间已不可用（被并发请求抢走，或复核发现重复售出）。
 *
 * <p>继承 {@link BusinessException}，因此即使一路透传到接口层，也仍是同样的 422 业务提示；
 * 而下单编排层可以凭类型区分它，从而换下一个候选房间重试，而不是直接失败。
 * 若用普通业务异常表达，编排层只能靠比对提示文案来判断，既脆弱又容易被文案改动悄悄破坏。</p>
 */
public class RoomUnavailableException extends BusinessException {

    public RoomUnavailableException(String message) {
        super(message);
    }
}
