package com.fastretry.core.spi.failure;

import com.fastretry.model.ctx.RetryTaskContext;
import lombok.Getter;

/**
 * 失败判定器 按异常类型给出决策
 */
public interface FailureDecider {

    /**
     * 根据异常做出决策
     */
    Decision decide(Throwable t, RetryTaskContext ctx);

    @Getter
    final class Decision {
        private final Outcome outcome;
        private final Category category;
        private final boolean preferSticky;
        private final double backoffFactor;
        private final String code;
        private final String message;

        private Decision(Outcome o, Category c, boolean s, double f, String code, String msg) {
            this.outcome = o; this.category = c; this.preferSticky = s; this.backoffFactor = f;
            this.code = code; this.message = msg;
        }
        public static Decision of(Outcome o, Category c) { return new Decision(o, c, true, 1.0, null, null); }
        public Decision sticky(boolean s){ return new Decision(outcome, category, s, backoffFactor, code, message); }
        public Decision factor(double f){ return new Decision(outcome, category, preferSticky, f, code, message); }
        public Decision withCode(String code){ return new Decision(outcome, category, preferSticky, backoffFactor, code, message); }
        public Decision withMsg(String msg){ return new Decision(outcome, category, preferSticky, backoffFactor, code, msg); }
    }

    public enum Outcome { RETRY, DEAD_LETTER, FAILED, PAUSED, CANCELLED }
    public enum Category { OPEN_CIRCUIT, RATE_LIMITED, BULKHEAD_FULL, TIMEOUT, IO, BIZ_4XX, BIZ_RETRYABLE, UNKNOWN }
}
