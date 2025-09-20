package com.fastretry.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fastretry.model.entity.RetryTaskEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RetryTaskMapper extends BaseMapper<RetryTaskEntity> {

    /**
     * 抢占到期任务（返回id列表，后续批量置RUNNING）
     */
    @Select("""
        select id from retry_task
        where state = 0 and next_trigger_time <= CURRENT_TIMESTAMP(3)
        order by priority desc, next_trigger_time asc
        limit #{limit}
        for update skip locked
    """)
    List<Long> lockDueTaskIds(@Param("limit") int limit);

    /**
     * 批量置 RUNNING
     */
    @Update({
            "<script>",
            "UPDATE retry_task",
            "   SET state = 1,",
            "       updated_at = CURRENT_TIMESTAMP(3),",
            "       version = version + 1",
            " WHERE state = 0",
            "   <if test='ids != null and ids.size() > 0'>",
            "     AND id IN",
            "     <foreach collection='ids' item='id' open='(' separator=',' close=')'>",
            "       #{id}",
            "     </foreach>",
            "   </if>",
            "   <if test='ids == null or ids.size() == 0'>",
            "     AND 1 = 0",
            "   </if>",
            "</script>"
    })
    int markRunningBatch(@Param("ids") List<Long> ids, @Param("nodeId") String nodeId);

    /**
     * 批量置 RUNNING
     * 粘滞绑定
     */
    @Update({
            "<script>",
            "UPDATE retry_task",
            "   SET state = 1,",
            "       owner_node_id = #{nodeId},",
            "       lease_expire_at = TIMESTAMPADD(MICROSECOND, #{ttlMs} * 1000, CURRENT_TIMESTAMP(3)),",
            "       fence_token = fence_token + 1,",
            "       updated_at = CURRENT_TIMESTAMP(3),",
            "       version = version + 1",
            " WHERE state = 0",
            "   <if test='ids != null and ids.size() > 0'>",
            "     AND id IN",
            "     <foreach collection='ids' item='id' open='(' separator=',' close=')'>",
            "       #{id}",
            "     </foreach>",
            "   </if>",
            "   <if test='ids == null or ids.size() == 0'>",
            "     AND 1 = 0",
            "   </if>",
            "</script>"
    })
    int markRunningAndOwnBatch(@Param("ids") List<Long> ids, @Param("nodeId") String nodeId, @Param("ttlMs") long ttlMs);

    /**
     * 提前续约
     */
    @Update("""
        UPDATE retry_task
           SET lease_expire_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL #{ttls} SECOND),
               updated_at = CURRENT_TIMESTAMP(3)
         WHERE id = #{id} AND state = 1 AND owner_node_id = #{nodeId}
           AND lease_expire_at <= DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL #{renewAhead} SECOND)
      """)
    int renewLease(@Param("id") Long id, @Param("nodeId") String nodeId,
                   @Param("ttls") long ttls, @Param("renewAhead") long renewAhead);

    /**
     * 本地重试写回
     */
    @Update("""
        UPDATE retry_task
           SET retry_count = #{retryCount},
               next_trigger_time = #{nextTriggerTime},
               last_error = #{lastError},
               updated_at = CURRENT_TIMESTAMP(3), version = version + 1
         WHERE id = #{id} AND state=1 AND owner_node_id = #{nodeId} AND version = #{version}
    """)
    int updateForLocalRetry(@Param("id") Long id, @Param("nodeId") String nodeId,
                            @Param("version") int version,
                            @Param("nextTriggerTime") LocalDateTime nextTriggerTime,
                            @Param("retryCount") int retryCount,
                            @Param("lastError") String lastError);

    /**
     * 接管查询
     */
    @Select("""
        SELECT id AS fence
          FROM retry_task
         WHERE state = 1 AND lease_expire_at<=CURRENT_TIMESTAMP(3)
         ORDER BY updated_at ASC
         LIMIT #{limit}
         FOR UPDATE SKIP LOCKED
      """)
    List<Long> findLeaseExpired(@Param("limit") int limit);

    /**
     * 尝试接管
     */
    @Update({
            "<script>",
            "UPDATE retry_task",
            "   SET owner_node_id = #{nodeId},",
            "       lease_expire_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL #{ttls} SECOND),",
            "       fence_token = fence_token + 1,",
            "       updated_at = CURRENT_TIMESTAMP(3)",
            " WHERE state = 1",
            "   <if test='ids != null and ids.size() > 0'>",
            "     AND id IN",
            "     <foreach collection='ids' item='x' open='(' separator=',' close=')'>",
            "       #{x}",
            "     </foreach>",
            "   </if>",
            "   <if test='ids == null or ids.size() == 0'>",
            "     AND 1 = 0",
            "   </if>",
            "</script>"
    })
    int tryTakeover(@Param("ids") List<Long> id, @Param("nodeId") String nodeId, @Param("ttls") long ttls);

    /**
     * 标记成功：仅允许 RUNNING(1) -> SUCCEED(2)，乐观锁校验 version。
     */
    @Update("""
        UPDATE retry_task
           SET state = 2,            -- SUCCEED
               updated_at = CURRENT_TIMESTAMP(3),
               version = version + 1
         WHERE id = #{id}
           AND state = 1             -- RUNNING
           AND version = #{version}
        """)
    int markSuccess(@Param("id") Long id, @Param("version") int version);

    /**
     * 失败后回到待重试：RUNNING(1) -> PENDING(0)，回写下一次触发时间/重试次数/错误信息;
     * nextTriggerTime 使用 LocalDateTime(3)；
     * lastError 截断至 4000 字符;
     */
    @Update("""
        UPDATE retry_task
           SET state = 0,                                -- PENDING
               retry_count = #{retryCount},
               next_trigger_time = #{nextTriggerTime},
               last_error = LEFT(#{lastError}, 4000),
               updated_at = CURRENT_TIMESTAMP(3),
               version = version + 1
         WHERE id = #{id}
           AND state = 1                                  -- RUNNING
           AND version = #{version}
        """)
    int markPendingWithNext(@Param("id") Long id,
                            @Param("version") int version,
                            @Param("nextTriggerTime")  LocalDateTime nextTriggerTime,
                            @Param("retryCount") int retryCount,
                            @Param("lastError") String lastError);

    /**
     * 进入 DLQ：允许从 PENDING(0)/RUNNING(1) 打入 DEAD_LETTER(6);
     * 如你希望放宽来源状态，可按需改 WHERE 条件;
     */
    @Update("""
        UPDATE retry_task
           SET state = 6,                                -- DEAD_LETTER
               last_error = LEFT(#{lastError}, 4000),
               updated_at = CURRENT_TIMESTAMP(3),
               version = version + 1
         WHERE id = #{id}
           AND state IN (0, 1)                           -- PENDING or RUNNING
           AND version = #{version}
        """)
    int markDeadLetter(@Param("id") Long id,
                       @Param("version") int version,
                       @Param("lastError") String lastError);
}
