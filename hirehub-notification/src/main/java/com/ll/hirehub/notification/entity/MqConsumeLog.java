package com.ll.hirehub.notification.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * MQ 消费幂等日志（见架构文档 §8.2）。
 * 唯一索引 {@code uk(message_id, consumer)} 把「至少一次投递」收敛成「效果上恰好一次」。
 */
@Data
@TableName("mq_consume_log")
public class MqConsumeLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String messageId;
    private String consumer;
    private LocalDateTime createTime;
}
