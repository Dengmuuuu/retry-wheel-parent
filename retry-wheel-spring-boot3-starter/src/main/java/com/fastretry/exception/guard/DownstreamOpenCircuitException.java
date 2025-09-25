package com.fastretry.exception.guard;

/**
 * 异常类型
 * 用于 FailureDecider/Backoff 识别 系统性故障
 */
public class DownstreamOpenCircuitException extends RuntimeException {

    public DownstreamOpenCircuitException(Throwable cause) {
        super("downstream circuit open", cause);
    }
}
