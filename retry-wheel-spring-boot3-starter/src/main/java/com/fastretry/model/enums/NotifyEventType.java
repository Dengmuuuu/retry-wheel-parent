package com.fastretry.model.enums;

/**
 * 通知事件
 */
public enum NotifyEventType {
    /** 进入死信 */
    DEAD_LETTER,

    /** 达到最大重试 */
    MAX_RETRY_REACHED,

    /** 不可重试的业务失败 */
    NON_RETRYABLE_FAILED,

    /** 被其他节点接管 */
    TAKEOVER,

    /** 续约失败 */
    LEASE_RENEW_FAILED,

    /** 持久化失败（Mapper/DB） */
    PERSIST_FAILED,

    /** 引擎级异常（线程池拒绝、调度异常等） */
    ENGINE_ERROR
}
