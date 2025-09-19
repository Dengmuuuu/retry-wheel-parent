package com.fastretry.core.spi;

import com.fastretry.config.RetryWheelProperties;
import com.fastretry.model.entity.RetryTaskEntity;

import java.time.Instant;

/**
 * 回退策略（计算下一次触发时间）
 */
public interface BackoffPolicy {

    /** 策略唯一名称（如 "fixed"、"exponential"、"myPolicy"） */
    String name();

    /**
     * 计算下一次触发时间
     * @param now        当前时间
     * @param attempt    第几次重试（从1开始更直观，也可从0，看你的引擎传入）
     * @param deadline   截止时间（可为 null）
     * @param task       任务实体（如需读取自定义 headers/attrs）
     * @param props      全局配置（读取 base/min/max/jitterRatio 等）
     * @return 下一次触发时间（不能超过 deadline）
     */
    Instant next(Instant now, int attempt, Instant deadline,
                 RetryTaskEntity task, RetryWheelProperties props);
}
