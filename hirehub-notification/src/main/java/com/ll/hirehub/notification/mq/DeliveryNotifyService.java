package com.ll.hirehub.notification.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ll.hirehub.common.mq.MqConst;
import com.ll.hirehub.common.mq.MqMessage;
import com.ll.hirehub.common.mq.MqPayload;
import com.ll.hirehub.notification.entity.MqConsumeLog;
import com.ll.hirehub.notification.mapper.MqConsumeLogMapper;
import com.ll.hirehub.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 投递域消息 → 站内通知（见架构文档 §6.2 通知映射 / §6.4）。
 * <p>
 * 与 listener 分开是为了让事务边界正确（详见 DeliveryCountService 的说明）：
 * 通知落库与幂等日志必须同一事务，失败整体回滚后重新入队。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryNotifyService {

    private static final String CONSUMER = MqConst.Consumer.NOTIFICATION;

    private final MqConsumeLogMapper consumeLogMapper;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    /** @return true 表示本次真的处理了；false 表示重复消息 */
    @Transactional(rollbackFor = Exception.class)
    public boolean handle(MqMessage message) throws JsonProcessingException {
        Long consumed = consumeLogMapper.selectCount(new LambdaQueryWrapper<MqConsumeLog>()
                .eq(MqConsumeLog::getMessageId, message.getMessageId())
                .eq(MqConsumeLog::getConsumer, CONSUMER));
        if (consumed != null && consumed > 0) {
            return false;
        }

        if (MqConst.BizType.DELIVERY_CREATED.equals(message.getBizType())) {
            handleDeliveryCreated(message);
        } else if (MqConst.BizType.DELIVERY_EVENT.equals(message.getBizType())) {
            handleDeliveryEvent(message);
        } else if (MqConst.BizType.INTERVIEW_REMIND.equals(message.getBizType())) {
            handleInterviewRemind(message);
        } else {
            log.warn("未知消息类型，跳过: {}", message.getBizType());
        }

        MqConsumeLog logRow = new MqConsumeLog();
        logRow.setMessageId(message.getMessageId());
        logRow.setConsumer(CONSUMER);
        logRow.setCreateTime(LocalDateTime.now());
        consumeLogMapper.insert(logRow);
        return true;
    }

    /** 投递创建 → 通知 HR */
    private void handleDeliveryCreated(MqMessage message) throws JsonProcessingException {
        MqPayload.DeliveryCreated payload =
                objectMapper.readValue(message.getPayload(), MqPayload.DeliveryCreated.class);
        List<Long> receivers = payload.getReceiverIds() == null
                ? Collections.emptyList() : payload.getReceiverIds();
        if (receivers.isEmpty()) {
            return;
        }
        notificationService.createInternal("DELIVERY_RECEIVED", "收到新的投递",
                String.format("「%s」收到新简历：%s", safe(payload.getJobTitle()), safe(payload.getSeekerName())),
                receivers);
    }

    /** 状态迁移 → 按 §6.2 通知映射决定接收人与文案 */
    private void handleDeliveryEvent(MqMessage message) throws JsonProcessingException {
        MqPayload.DeliveryEvent payload =
                objectMapper.readValue(message.getPayload(), MqPayload.DeliveryEvent.class);
        List<Long> receivers = payload.getReceiverIds() == null
                ? Collections.emptyList() : payload.getReceiverIds();
        if (receivers.isEmpty()) {
            return;
        }
        String event = payload.getEvent();
        String title;
        if ("CONTACT".equals(event)) {
            title = "HR 已与你发起沟通";
        } else if ("INVITE_INTERVIEW".equals(event)) {
            title = "收到面试邀请";
        } else if ("SEND_OFFER".equals(event)) {
            title = "恭喜，收到 Offer";
        } else if ("REJECT".equals(event)) {
            title = "投递未通过";
        } else if ("HIRE".equals(event)) {
            title = "恭喜入职";
        } else if ("CANCEL".equals(event)) {
            title = "求职者取消了投递";
        } else {
            log.warn("未配置文案的事件，跳过通知: {}", event);
            return;
        }
        notificationService.createInternal("DELIVERY_" + event, title,
                String.format("职位「%s」：%s → %s", safe(payload.getJobTitle()),
                        safe(payload.getFromStatus()), safe(payload.getToStatus())),
                receivers);
    }

    /**
     * 面试提醒 → 站内通知（见 D-32）。
     * 文案已由 interview 服务生成好（它掌握面试时间与编号），这里只落库分发——
     * 不在这里拼文案，是为了不让 notification 反向依赖 interview 的字段结构。
     */
    private void handleInterviewRemind(MqMessage message) throws JsonProcessingException {
        MqPayload.InterviewRemind payload =
                objectMapper.readValue(message.getPayload(), MqPayload.InterviewRemind.class);
        List<Long> receivers = payload.getReceiverIds() == null
                ? Collections.emptyList() : payload.getReceiverIds();
        if (receivers.isEmpty()) {
            return;
        }
        notificationService.createInternal("INTERVIEW_REMIND", "面试提醒",
                safe(payload.getContent()), receivers);
    }

    private String safe(String s) {
        return s == null ? "-" : s;
    }
}
