package com.fastretry.core.failure;

import com.fastretry.core.spi.failure.FailureCaseHandler;
import com.fastretry.core.spi.failure.FailureDecider;
import com.fastretry.model.ctx.RetryTaskContext;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class RouterFailureDecider implements FailureDecider {

    private final List<FailureCaseHandler<?>> handlers;

    /** 未匹配时的默认决策 */
    private final Decision defaultDecision;

    public RouterFailureDecider(List<FailureCaseHandler<?>> handlers) {
        this(handlers, Decision.of(Outcome.DEAD_LETTER, Category.UNKNOWN).withCode("UNHANDLED"));
    }

    public RouterFailureDecider(List<FailureCaseHandler<?>> handlers, Decision defaultDecision) {
        // 去重 + 稳定顺序 具体类优先（子类在前 父类在后）
        this.handlers = handlers;
        this.defaultDecision = defaultDecision;
    }

    /**
     * 同类型匹配时选择离异常类最近的处理器
     */
    @Override
    public Decision decide(Throwable t, RetryTaskContext ctx) {
        // 展开 cause 链 先本体, 再逐级cause
        for (Throwable e = t; e != null; e = e.getCause()) {
            FailureCaseHandler<?> matched =findBestHandler(e);
            if (matched != null) {
                return safeCall(matched, e, ctx);
            }
        }
        return defaultDecision;
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private Decision safeCall(FailureCaseHandler h, Throwable e, RetryTaskContext ctx) {
        return h.execute(e, ctx);
    }

    private FailureCaseHandler<?> findBestHandler(Throwable e) {
        // 过滤 supports 再按继承层级深度排序
        return handlers.stream()
                .filter(h -> h.supports(e))
                .min(Comparator.comparingInt(h -> distance(e.getClass(), h.exceptionType())))
                .orElse(null);
    }

    private static int distance(Class<?> from, Class<?> to) {
        // 计算from向上继承到to的距离
        int d = 0;
        Class<?> c = from;
        while (c != null && !to.equals(c)) {
            c = c.getSuperclass();
            ++ d;
        }
        return (c == null) ? Integer.MAX_VALUE : d;
    }

    private static List<FailureCaseHandler<?>> orderBySpecificity(List<FailureCaseHandler<?>> ins) {
        return ins.stream().distinct().collect(Collectors.toList());
    }
}
