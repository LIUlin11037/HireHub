package com.ll.hirehub.notification.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ll.hirehub.common.mq.MqConst;
import com.ll.hirehub.common.mq.MqMessage;
import com.ll.hirehub.common.mq.MqPayload;
import com.ll.hirehub.notification.entity.MqConsumeLog;
import com.ll.hirehub.notification.mapper.MqConsumeLogMapper;
import com.ll.hirehub.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 通知消费服务（§6.2 / §6.4）单元测试。
 * 核心不变式：消费去重（mq_consume_log）与通知落库同事务；
 * 「重复消息」与「已处理」必须可区分——返回值 false 驱动 consumer 的后续动作。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeliveryNotifyServiceTest {

    @Mock
    private MqConsumeLogMapper consumeLogMapper;
    @Mock
    private NotificationService notificationService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DeliveryNotifyService service;

    @BeforeEach
    void setUp() {
        service = new DeliveryNotifyService(consumeLogMapper, notificationService, objectMapper);
    }

    private MqMessage messageOf(String bizType, Object payload) throws Exception {
        MqMessage m = new MqMessage();
        m.setMessageId("msg-1");
        m.setBizType(bizType);
        m.setBizId(1L);
        m.setPayload(objectMapper.writeValueAsString(payload));
        return m;
    }

    private MqPayload.DeliveryCreated createdPayload() {
        MqPayload.DeliveryCreated p = new MqPayload.DeliveryCreated();
        p.setDeliveryId(1L);
        p.setJobTitle("Java 后端");
        p.setSeekerName("张三");
        p.setReceiverIds(List.of(3L, 4L));
        return p;
    }

    @Test
    @DisplayName("DELIVERY_CREATED 首消费 → 通知全部 HR 接收人 + 写消费日志")
    void createdNotifiesReceivers() throws Exception {
        when(consumeLogMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        boolean processed = service.handle(messageOf(MqConst.BizType.DELIVERY_CREATED, createdPayload()));

        assertThat(processed).isTrue();
        verify(notificationService).createInternal(eq("DELIVERY_RECEIVED"), eq("收到新的投递"),
                anyString(), eq(List.of(3L, 4L)));
        verify(consumeLogMapper).insert(any(MqConsumeLog.class));
    }

    @Test
    @DisplayName("重复消息（已有消费日志）→ 返回 false，不发通知、不重复写日志")
    void duplicateMessageSkipped() throws Exception {
        when(consumeLogMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        boolean processed = service.handle(messageOf(MqConst.BizType.DELIVERY_CREATED, createdPayload()));

        assertThat(processed).isFalse();
        verifyNoInteractions(notificationService);
        verify(consumeLogMapper, never()).insert(any(MqConsumeLog.class));
    }

    @Test
    @DisplayName("DELIVERY_EVENT：CANCEL → 通知文案映射为「求职者取消了投递」")
    void cancelEventNotificationMapping() throws Exception {
        when(consumeLogMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        MqPayload.DeliveryEvent p = new MqPayload.DeliveryEvent();
        p.setEvent("CANCEL");
        p.setJobTitle("Java 后端");
        p.setFromStatus("PENDING");
        p.setToStatus("CANCELLED");
        p.setReceiverIds(List.of(7L));

        boolean processed = service.handle(messageOf(MqConst.BizType.DELIVERY_EVENT, p));

        assertThat(processed).isTrue();
        verify(notificationService).createInternal(eq("DELIVERY_CANCEL"), eq("求职者取消了投递"),
                anyString(), eq(List.of(7L)));
    }

    @Test
    @DisplayName("DELIVERY_EVENT：未配置文案的事件 → 不发通知，但仍记消费日志（不无限重投）")
    void unknownEventStillConsumed() throws Exception {
        when(consumeLogMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        MqPayload.DeliveryEvent p = new MqPayload.DeliveryEvent();
        p.setEvent("SOMETHING_NEW");
        p.setReceiverIds(List.of(7L));

        boolean processed = service.handle(messageOf(MqConst.BizType.DELIVERY_EVENT, p));

        assertThat(processed).isTrue();
        verify(notificationService, never()).createInternal(anyString(), anyString(), anyString(), anyList());
        verify(consumeLogMapper).insert(any(MqConsumeLog.class));
    }

    @Test
    @DisplayName("接收人为空 → 不发通知但仍记消费日志（消息视为成功消费）")
    void emptyReceiversStillConsumed() throws Exception {
        when(consumeLogMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        MqPayload.DeliveryCreated p = createdPayload();
        p.setReceiverIds(null);

        boolean processed = service.handle(messageOf(MqConst.BizType.DELIVERY_CREATED, p));

        assertThat(processed).isTrue();
        verify(notificationService, never()).createInternal(anyString(), anyString(), anyString(), anyList());
        verify(consumeLogMapper).insert(any(MqConsumeLog.class));
    }

    @Test
    @DisplayName("INTERVIEW_EVENT → 透传 interview 生成好的标题与文案（R-02：改期/取消通知对方）")
    void interviewEventPassThrough() throws Exception {
        when(consumeLogMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        MqPayload.InterviewEvent p = new MqPayload.InterviewEvent();
        p.setInterviewId(10L);
        p.setEvent("RESCHEDULED");
        p.setTitle("面试改期通知");
        p.setContent("您的面试（第 1 轮）时间已调整为 2026-10-20 14:00，请重新确认。");
        p.setReceiverIds(List.of(5L));

        boolean processed = service.handle(messageOf(MqConst.BizType.INTERVIEW_EVENT, p));

        assertThat(processed).isTrue();
        verify(notificationService).createInternal(eq("INTERVIEW_RESCHEDULED"), eq("面试改期通知"),
                eq("您的面试（第 1 轮）时间已调整为 2026-10-20 14:00，请重新确认。"), eq(List.of(5L)));
        verify(consumeLogMapper).insert(any(MqConsumeLog.class));
    }

    @Test
    @DisplayName("INTERVIEW_REMIND → 直接落库分发，不二次拼文案（不反向依赖 interview 字段结构）")
    void interviewRemindPassThrough() throws Exception {
        when(consumeLogMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        MqPayload.InterviewRemind p = new MqPayload.InterviewRemind();
        p.setInterviewId(10L);
        p.setContent("明天 14:00 面试，请准时参加");
        p.setReceiverIds(List.of(5L));

        boolean processed = service.handle(messageOf(MqConst.BizType.INTERVIEW_REMIND, p));

        assertThat(processed).isTrue();
        verify(notificationService).createInternal(eq("INTERVIEW_REMIND"), eq("面试提醒"),
                eq("明天 14:00 面试，请准时参加"), eq(List.of(5L)));
    }
}
