package com.ll.hirehub.resume.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ll.hirehub.common.mq.MqConst;
import com.ll.hirehub.common.mq.MqMessage;
import com.ll.hirehub.common.mq.MqPayload;
import com.ll.hirehub.resume.entity.MqConsumeLog;
import com.ll.hirehub.resume.mapper.MqConsumeLogMapper;
import com.ll.hirehub.resume.service.ResumeParseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 简历解析消息处理（幂等 + 事务边界，见架构文档 §8.2）。
 * <p>
 * 与 listener 分开：异常要能抛出方法外才能让事务回滚、由 listener 决定 nack。
 * <p>
 * 注意一个刻意的取舍：{@link ResumeParseService#parse} 内部<b>自己吞掉</b>解析异常
 * （失败写 parse_status=3），所以"文件坏了"这类业务失败<b>不会</b>触发无限重试——
 * 重试解决不了坏文件，只会堵住队列。用户可以调 {@code /reparse} 主动重试。
 * 只有基础设施异常（DB / 反序列化）才会 nack 重入队。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeParseHandler {

    private static final String CONSUMER = MqConst.Consumer.RESUME_PARSE;

    private final MqConsumeLogMapper consumeLogMapper;
    private final ResumeParseService parseService;
    private final ObjectMapper objectMapper;

    /** @return true 表示本次真的处理了；false 表示重复消息 */
    @Transactional(rollbackFor = Exception.class)
    public boolean handle(MqMessage message) throws Exception {
        Long consumed = consumeLogMapper.selectCount(new LambdaQueryWrapper<MqConsumeLog>()
                .eq(MqConsumeLog::getMessageId, message.getMessageId())
                .eq(MqConsumeLog::getConsumer, CONSUMER));
        if (consumed != null && consumed > 0) {
            return false;
        }

        MqPayload.ResumeParse payload =
                objectMapper.readValue(message.getPayload(), MqPayload.ResumeParse.class);
        parseService.parse(payload.getResumeId(), payload.getObjectKey());

        MqConsumeLog logRow = new MqConsumeLog();
        logRow.setMessageId(message.getMessageId());
        logRow.setConsumer(CONSUMER);
        logRow.setCreateTime(LocalDateTime.now());
        consumeLogMapper.insert(logRow);   // 并发下由 uk(message_id, consumer) 兜底
        return true;
    }
}
