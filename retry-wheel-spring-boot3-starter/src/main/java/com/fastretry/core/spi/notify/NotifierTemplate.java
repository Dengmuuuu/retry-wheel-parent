package com.fastretry.core.spi.notify;

import com.fastretry.model.ctx.NotifyContext;
import com.fastretry.model.enums.Severity;

public interface NotifierTemplate {
    /** 渲染标题 */
    String renderTitle(NotifyContext ctx, Severity sev);

    /** 渲染内容 */
    String renderBody(NotifyContext ctx, Severity sev);
}
