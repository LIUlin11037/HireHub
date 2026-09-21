package com.ll.hirehub.job.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * MQ 消费幂等日志（见架构文档 §8.2）。
 * <p>
 * 唯一索引 {@code uk(message_id, consumer)} 是「至少一次投递」变成
 * 「效果上恰好一次」的关键：重复消息在插入这一步就被数据库挡住。
 * <p>
 * 故意不加 {@code @TableLogic}——日志不逻辑删除。
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
