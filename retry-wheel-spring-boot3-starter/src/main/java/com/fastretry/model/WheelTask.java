package com.fastretry.model;

import com.fastretry.model.entity.RetryTaskEntity;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 本地时间轮上的`业务型`任务封装
 * 让时间轮返回的 Timeout 能识别任务信息
 */
public class WheelTask implements TimerTask {

    public enum Kind { EXECUTE, RETRY, SCANNER_WAKEUP }

    private final Kind kind;

    private final RetryTaskEntity task;

    /** 是否粘滞模式 */
    private final boolean sticky;

    /** 真正要执行的逻辑 */
    private final Runnable actual;

    public WheelTask(Kind kind, RetryTaskEntity task, boolean sticky, Runnable actual) {
        this.kind = kind;
        this.task = task;
        this.sticky = sticky;
        this.actual = actual;
    }

    @Override
    public void run(Timeout timeout) throws Exception {
        if (kind == Kind.SCANNER_WAKEUP) {
            return;
        }
        actual.run();
    }

    public Kind getKind() {
        return kind;
    }

    public RetryTaskEntity getTask() {
        return task;
    }

    public boolean isSticky() {
        return sticky;
    }

    public Runnable getActual() {
        return actual;
    }
}
