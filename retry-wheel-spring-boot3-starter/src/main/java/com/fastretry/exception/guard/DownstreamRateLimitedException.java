package com.fastretry.exception.guard;

public class DownstreamRateLimitedException extends RuntimeException {
    public DownstreamRateLimitedException(Throwable cause) { super("downstream rate limited", cause); }
}
