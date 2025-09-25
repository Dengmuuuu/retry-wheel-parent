package com.fastretry.exception.guard;

public class DownstreamBulkheadFullException extends RuntimeException {
    public DownstreamBulkheadFullException(Throwable cause) { super("downstream bulkhead full", cause); }
}
