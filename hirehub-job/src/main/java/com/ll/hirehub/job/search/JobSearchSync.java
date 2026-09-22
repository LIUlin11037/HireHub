package com.ll.hirehub.job.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ll.hirehub.common.mq.MqConst;
import com.ll.hirehub.common.mq.MqMessage;
import com.ll.hirehub.common.mq.MqPayload;
import com.ll.hirehub.job.entity.Job;
import com.ll.hirehub.job.entity.MqConsumeLog;
import com.ll.hirehub.job.mapper.JobMapper;
import com.ll.hirehub.job.mapper.MqConsumeLogMapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 职位 MySQL → ES 的 MQ 异步同步（见架构文档 §6.3 / D-09）。
 * <p>
 * 三段式：
 * <ol>
 *   <li>业务事务提交后（AFTER_COMMIT）才发 MQ——避免「DB 回滚了消息却发出去了」；</li>
 *   <li>本服务自消费 {@code job.upsert}/{@code job.delete}，写 / 删 {@code job_index}；</li>
 *   <li>对账任务（{@link JobSearchService#reconcile()}）兜底「消息丢失 / 消费失败」的窗口。</li>
 * </ol>
 * 幂等：消费成功写 {@code mq_consume_log}（uk(message_id, consumer)），重复消息直接 ACK 跳过。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobSearchSync implements ApplicationRunner {

    private static final String CONSUMER = MqConst.Consumer.JOB_SEARCH;

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final JobSearchService jobSearchService;
    private final JobMapper jobMapper;
    private final MqConsumeLogMapper mqConsumeLogMapper;

    /** 启动即建索引 + 全量灌一次（历史数据 / 冷启动） */
    @Override
    public void run(ApplicationArguments args) {
        jobSearchService.ensureIndex();
        List<Job> jobs = jobMapper.selectList(new LambdaQueryWrapper<Job>().eq(Job::getStatus, 1));
        jobs.forEach(jobSearchService::index);
        log.info("job_index 启动初始化完成，索引 {} 个招聘中职位", jobs.size());
    }

    /** 业务事务提交后才发 MQ */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onJobChanged(JobSyncEvent event) {
        MqMessage msg = new MqMessage();
        msg.setMessageId(UUID.randomUUID().toString().replace("-", ""));
        msg.setBizType(event.isDelete() ? MqConst.BizType.JOB_DELETE : MqConst.BizType.JOB_UPSERT);
        msg.setBizId(event.getJobId());
        msg.setPayload("{\"jobId\":" + event.getJobId() + "}");
        String routingKey = event.isDelete() ? MqConst.RK_JOB_DELETE : MqConst.RK_JOB_UPSERT;
        rabbitTemplate.convertAndSend(MqConst.EXCHANGE, routingKey, msg);
    }

    @RabbitListener(queues = MqConst.QUEUE_JOB_SEARCH)
    public void onSync(MqMessage message, Channel channel,
                       @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            Long consumed = mqConsumeLogMapper.selectCount(new LambdaQueryWrapper<MqConsumeLog>()
                    .eq(MqConsumeLog::getMessageId, message.getMessageId())
                    .eq(MqConsumeLog::getConsumer, CONSUMER));
            if (consumed != null && consumed > 0) {
                channel.basicAck(deliveryTag, false);
                return;
            }

            MqPayload.JobSync payload = objectMapper.readValue(message.getPayload(), MqPayload.JobSync.class);
            if (MqConst.BizType.JOB_DELETE.equals(message.getBizType())) {
                jobSearchService.delete(payload.getJobId());
            } else {
                Job job = jobMapper.selectById(payload.getJobId());
                if (job != null) {
                    jobSearchService.index(job);
                }
            }

            MqConsumeLog logRow = new MqConsumeLog();
            logRow.setMessageId(message.getMessageId());
            logRow.setConsumer(CONSUMER);
            logRow.setCreateTime(LocalDateTime.now());
            mqConsumeLogMapper.insert(logRow);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("职位 ES 同步失败，重新入队: messageId={}, err={}", message.getMessageId(), e.getMessage(), e);
            channel.basicNack(deliveryTag, false, true);
        }
    }
}
