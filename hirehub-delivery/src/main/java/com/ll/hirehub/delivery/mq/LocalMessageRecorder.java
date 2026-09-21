package com.ll.hirehub.delivery.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ll.hirehub.common.exception.BusinessException;
import com.ll.hirehub.delivery.entity.LocalMessage;
import com.ll.hirehub.delivery.mapper.LocalMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 本地消息写入器（见架构文档 §6.2 步骤①）。
 * <p>
 * <b>必须在业务事务内调用</b>：本类不做任何独立提交，
 * 靠「与业务同库同事务」保证原子性——业务回滚则消息一起回滚。
 */
@Component
@RequiredArgsConstructor
public class LocalMessageRecorder {

    private final LocalMessageMapper localMessageMapper;
    private final ObjectMapper objectMapper;

    /**
     * @param bizType    业务类型，见 {@code MqConst.BizType}
     * @param bizId      业务主键（deliveryId）
     * @param routingKey MQ 路由键
     * @param payload    负载对象，序列化为 JSON 后落库
     * @return 生成的消息 ID（消费端幂等键）
     */
    public String record(String bizType, Long bizId, String routingKey, Object payload) {
        LocalMessage message = new LocalMessage();
        message.setMessageId(UUID.randomUUID().toString().replace("-", ""));
        message.setBizType(bizType);
        message.setBizId(bizId);
        message.setRoutingKey(routingKey);
        message.setPayload(toJson(payload));
        message.setStatus(LocalMessage.STATUS_PENDING);
        message.setRetryCount(0);
        message.setNextRetryTime(LocalDateTime.now());
        localMessageMapper.insert(message);
        return message.getMessageId();
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new BusinessException("本地消息序列化失败：" + e.getMessage());
        }
    }
}
