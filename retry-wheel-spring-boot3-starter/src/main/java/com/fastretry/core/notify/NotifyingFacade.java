package com.fastretry.core.notify;

import com.fastretry.model.ctx.NotifyContext;
import com.fastretry.model.enums.Severity;
import org.springframework.beans.factory.ObjectProvider;

import java.util.function.Supplier;

public class NotifyingFacade {
    private final Supplier<AsyncNotifyingService> delegate;

    public NotifyingFacade(ObjectProvider<AsyncNotifyingService> p) {
        // 未启用notify则为 null
        this.delegate = p::getIfAvailable;
    }

    public void fire(NotifyContext ctx, Severity sev) {
        AsyncNotifyingService s = delegate.get();
        if (s != null) s.fire(ctx, sev);
    }
}

