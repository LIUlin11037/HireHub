package com.ll.hirehub.job.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ll.hirehub.common.mq.MqConst;
import com.ll.hirehub.common.mq.MqMessage;
import com.ll.hirehub.common.mq.MqPayload;
import com.ll.hirehub.job.entity.Job;
import com.ll.hirehub.job.entity.MqConsumeLog;
import com.ll.hirehub.job.mapper.JobMapper;
import com.ll.hirehub.job.mapper.MqConsumeLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 消费「投递创建」消息：职位投递数 +1（见架构文档 §6.2 步骤③）。
 * <p>
 * 与 listener 分开是为了让事务边界正确：异常必须抛出方法外，
 * 事务才会回滚，listener 再决定 nack 重入队。若把 try/catch 写在
 * {@code @Transactional} 方法内部，异常被吞掉 → 事务照样提交 → 幂等日志与业务一起脏。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryCountService {

    private static final String CONSUMER = MqConst.Consumer.JOB_DELIVERY_COUNT;

    private final MqConsumeLogMapper consumeLogMapper;
    private final JobMapper jobMapper;
    private final ObjectMapper objectMapper;

    /**
     * @return true 表示本次真的处理了；false 表示重复消息已消费过
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean handle(MqMessage message) throws JsonProcessingException {
        Long consumed = consumeLogMapper.selectCount(new LambdaQueryWrapper<MqConsumeLog>()
                .eq(MqConsumeLog::getMessageId, message.getMessageId())
                .eq(MqConsumeLog::getConsumer, CONSUMER));
        if (consumed != null && consumed > 0) {
            return false;
        }

        MqPayload.DeliveryCreated payload =
                objectMapper.readValue(message.getPayload(), MqPayload.DeliveryCreated.class);
        if (payload.getJobId() != null) {
            // 用 SQL 自增而不是「查出来 +1 再写回」：避免读改写丢更新
            jobMapper.update(null, new LambdaUpdateWrapper<Job>()
                    .eq(Job::getId, payload.getJobId())
                    .setSql("delivery_count = delivery_count + 1"));
        }

        MqConsumeLog logRow = new MqConsumeLog();
        logRow.setMessageId(message.getMessageId());
        logRow.setConsumer(CONSUMER);
        logRow.setCreateTime(LocalDateTime.now());
        consumeLogMapper.insert(logRow); // 并发下由 uk(message_id, consumer) 兜底
        return true;
    }
}
