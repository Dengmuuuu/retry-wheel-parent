package com.fastretry.core.spi;

import com.fastretry.model.entity.RetryTaskEntity;

/**
 * 分片策略
 */
public interface ShardingStrategy {

    boolean accept(RetryTaskEntity task, int shardIndex, int shardCount);

}
