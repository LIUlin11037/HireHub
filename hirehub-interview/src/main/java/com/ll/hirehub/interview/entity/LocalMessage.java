package com.ll.hirehub.interview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 本地消息表（审计 R-02 / §6.9）：与 interview 同库同事务写入，复用 D-01 机制。
 * <p>
 * 面试创建/改期/取消在业务事务内落一条消息，后台 relay 投递到 MQ，
 * 保证「面试变更成功 ⟺ 对方一定能收到通知」的最终一致性。
 * <p>
 * 已确认的消息由清理任务物理删除（保留 7 天便于排查），不逻辑删除。
 */
@Data
@TableName("local_message")
public class LocalMessage {

    /** 待发送 */
    public static final int STATUS_PENDING = 0;
    /** 已投递到 MQ（publisher confirm 成功） */
    public static final int STATUS_SENT = 1;
    /** 消费端已确认 */
    public static final int STATUS_CONFIRMED = 2;
    /** 重试超限，需人工介入 */
    public static final int STATUS_FAILED = 3;

    /** 重试上限，超过即置为失败并告警 */
    public static final int MAX_RETRY = 5;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String messageId;
    private String bizType;
    private Long bizId;
    private String routingKey;
    private String payload;

    private Integer status;
    private Integer retryCount;
    private LocalDateTime nextRetryTime;
    private LocalDateTime sendTime;
    private LocalDateTime ackTime;
    private String errorMsg;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
