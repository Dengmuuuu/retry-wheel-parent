package com.fastretry.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Task状态
 */
@AllArgsConstructor
@Getter
public enum TaskState {
    PENDING(0, "待调度/到期待执行"),
    RUNNING(1, "执行中（被某实例独占）"),
    SUCCEED(2, "执行完成，终态"),
    FAILED(3, "明确的永久性业务失败（不可重试且无需人工复核），终态"),
    PAUSED(4, "死信（不可重试但需要人工复核/可拉回），终态"),
    CANCELLED(5, "暂停（运维/策略临时冻结），非终态，可恢复"),
    DEAD_LETTER(6, "取消（人为或上游撤销），终态")
    ;


    public final int code;
    public final String desc;
}
