package com.fastretry.core.spi;

import com.fastretry.model.ctx.RetryTaskContext;
import com.fastretry.model.entity.RetryTaskEntity;

/**
 * 告警通知器（进入DLQ/连续失败阈值等）
 */
public interface Notifier {

    void notify(RetryTaskContext ctx, RetryTaskEntity task, String message);

}
