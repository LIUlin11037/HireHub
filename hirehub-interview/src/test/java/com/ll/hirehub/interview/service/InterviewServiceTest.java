package com.ll.hirehub.interview.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.ll.hirehub.api.CompanyClient;
import com.ll.hirehub.api.DeliveryClient;
import com.ll.hirehub.api.dto.CompanyMemberDTO;
import com.ll.hirehub.api.dto.DeliveryDTO;
import com.ll.hirehub.common.exception.BusinessException;
import com.ll.hirehub.common.mq.MqConst;
import com.ll.hirehub.common.result.Result;
import com.ll.hirehub.common.result.ResultCode;
import com.ll.hirehub.interview.dto.CancelRequest;
import com.ll.hirehub.interview.dto.CreateInterviewRequest;
import com.ll.hirehub.interview.dto.FeedbackRequest;
import com.ll.hirehub.interview.dto.RescheduleRequest;
import com.ll.hirehub.interview.entity.Interview;
import com.ll.hirehub.interview.entity.InterviewFeedback;
import com.ll.hirehub.interview.enums.InterviewStatus;
import com.ll.hirehub.interview.mapper.InterviewFeedbackMapper;
import com.ll.hirehub.interview.mapper.InterviewMapper;
import com.ll.hirehub.interview.mq.InterviewReminderService;
import com.ll.hirehub.interview.statemachine.InterviewStateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 面试服务（D-28 / D-32）单元测试。
 * 覆盖：创建校验、五态迁移的角色绑定、改期重排提醒、取消/完成后的提醒清理。
 * 状态机本体由 InterviewStateMachineTest 覆盖，这里使用真实状态机串联。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InterviewServiceTest {

    @Mock
    private InterviewMapper interviewMapper;
    @Mock
    private InterviewFeedbackMapper feedbackMapper;
    @Mock
    private DeliveryClient deliveryClient;
    @Mock
    private CompanyClient companyClient;
    @Mock
    private InterviewReminderService reminderService;
    @Mock
    private com.ll.hirehub.interview.mq.LocalMessageRecorder localMessageRecorder;

    private InterviewService service;

    private static final Long SEEKER = 5L;
    private static final Long HR = 7L;
    private static final Long COMPANY = 9L;

    @BeforeEach
    void setUp() {
        // 纯单元测试没有 MyBatis 上下文，Lambda 包装器解析列名需要实体 TableInfo，手动初始化
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Interview.class);
        service = new InterviewService(interviewMapper, feedbackMapper, new InterviewStateMachine(),
                deliveryClient, companyClient, reminderService, localMessageRecorder);
        // 条件更新（乐观锁）默认成功
        when(interviewMapper.update(any(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(1);
    }

    private DeliveryDTO deliveryOf(String status) {
        DeliveryDTO d = new DeliveryDTO();
        d.setId(1L);
        d.setJobId(100L);
        d.setCompanyId(COMPANY);
        d.setSeekerId(SEEKER);
        d.setStatus(status);
        return d;
    }

    private CompanyMemberDTO hrMember() {
        CompanyMemberDTO m = new CompanyMemberDTO();
        m.setCompanyId(COMPANY);
        m.setUserId(HR);
        m.setRole("HR");
        m.setStatus(1);
        return m;
    }

    private Interview interviewOf(String status) {
        Interview iv = new Interview();
        iv.setId(10L);
        iv.setDeliveryId(1L);
        iv.setCompanyId(COMPANY);
        iv.setSeekerId(SEEKER);
        iv.setStatus(status);
        iv.setInterviewTime(LocalDateTime.now().plusDays(2));
        return iv;
    }

    private CreateInterviewRequest createRequest() {
        CreateInterviewRequest req = new CreateInterviewRequest();
        req.setDeliveryId(1L);
        req.setInterviewTime(LocalDateTime.now().plusDays(2));
        req.setInterviewType("VIDEO");
        return req;
    }

    // -------------------- create --------------------

    @Test
    @DisplayName("create：投递不存在 → 拒绝")
    void createRejectsMissingDelivery() {
        when(deliveryClient.getDelivery(1L)).thenReturn(Result.ok(null));

        assertThatThrownBy(() -> service.create(HR, createRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("投递不存在");
        verify(interviewMapper, never()).insert(any(Interview.class));
    }

    @Test
    @DisplayName("create：非该企业在职 HR → 403")
    void createRejectsNonMember() {
        when(deliveryClient.getDelivery(1L)).thenReturn(Result.ok(deliveryOf("COMMUNICATING")));
        when(companyClient.getMember(COMPANY, HR)).thenReturn(Result.ok(null));

        assertThatThrownBy(() -> service.create(HR, createRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));
        verify(interviewMapper, never()).insert(any(Interview.class));
    }

    @Test
    @DisplayName("create：成功 → round=历史场次+1，状态 SCHEDULED，同事务排布提醒（D-32）")
    void createSuccessSchedulesReminders() {
        when(deliveryClient.getDelivery(1L)).thenReturn(Result.ok(deliveryOf("COMMUNICATING")));
        when(companyClient.getMember(COMPANY, HR)).thenReturn(Result.ok(hrMember()));
        when(interviewMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(2L);
        doAnswer(inv -> {
            inv.<Interview>getArgument(0).setId(50L);
            return 1;
        }).when(interviewMapper).insert(any(Interview.class));

        Long id = service.create(HR, createRequest());

        assertThat(id).isEqualTo(50L);
        ArgumentCaptor<Interview> captor = ArgumentCaptor.forClass(Interview.class);
        verify(reminderService).schedule(captor.capture());
        Interview saved = captor.getValue();
        assertThat(saved.getRound()).isEqualTo(3);
        assertThat(saved.getStatus()).isEqualTo(InterviewStatus.SCHEDULED.name());
        assertThat(saved.getSeekerId()).isEqualTo(SEEKER);
        // R-02：同事务写本地消息，通知求职者
        verify(localMessageRecorder).record(eq(MqConst.BizType.INTERVIEW_EVENT), eq(50L),
                eq(MqConst.RK_INTERVIEW_EVENT), any(com.ll.hirehub.common.mq.MqPayload.InterviewEvent.class));
    }

    @Test
    @DisplayName("create：终态投递（REJECTED/CANCELLED）→ 拒绝发起面试（F-04，状态机白名单不可旁路）")
    void createRejectsTerminalDelivery() {
        when(deliveryClient.getDelivery(1L)).thenReturn(Result.ok(deliveryOf("REJECTED")));
        when(companyClient.getMember(COMPANY, HR)).thenReturn(Result.ok(hrMember()));

        assertThatThrownBy(() -> service.create(HR, createRequest()))
                .isInstanceOf(BusinessException.class);
        verify(interviewMapper, never()).insert(any(Interview.class));
    }

    // -------------------- 状态迁移与角色 --------------------

    @Test
    @DisplayName("confirm：求职者确认 SCHEDULED → CONFIRMED")
    void seekerConfirms() {
        Interview iv = interviewOf(InterviewStatus.SCHEDULED.name());
        when(interviewMapper.selectById(10L)).thenReturn(iv);

        service.confirm(SEEKER, 10L);

        assertThat(iv.getStatus()).isEqualTo(InterviewStatus.CONFIRMED.name());
        verify(interviewMapper).update(any(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    @Test
    @DisplayName("confirm：HR 不能替求职者确认 → 403")
    void hrCannotConfirmForSeeker() {
        Interview iv = interviewOf(InterviewStatus.SCHEDULED.name());
        when(interviewMapper.selectById(10L)).thenReturn(iv);
        when(companyClient.getMember(COMPANY, HR)).thenReturn(Result.ok(hrMember()));

        assertThatThrownBy(() -> service.confirm(HR, 10L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));
    }

    @Test
    @DisplayName("reject：求职者拒绝 → REJECTED 且清理提醒")
    void seekerRejectsAndCancelsReminders() {
        Interview iv = interviewOf(InterviewStatus.SCHEDULED.name());
        when(interviewMapper.selectById(10L)).thenReturn(iv);

        service.reject(SEEKER, 10L);

        assertThat(iv.getStatus()).isEqualTo(InterviewStatus.REJECTED.name());
        verify(reminderService).cancel(10L);
    }

    @Test
    @DisplayName("cancel：记录取消方与原因，清理提醒，并通知另一方（R-02：求职者取消 → 通知企业 HR）")
    void cancelRecordsActorAndReason() {
        Interview iv = interviewOf(InterviewStatus.CONFIRMED.name());
        when(interviewMapper.selectById(10L)).thenReturn(iv);
        when(companyClient.listMemberUserIds(COMPANY)).thenReturn(Result.ok(java.util.List.of(HR)));
        CancelRequest req = new CancelRequest();
        req.setReason("时间冲突");

        service.cancel(SEEKER, 10L, req);

        assertThat(iv.getStatus()).isEqualTo(InterviewStatus.CANCELLED.name());
        assertThat(iv.getCancelledBy()).isEqualTo("SEEKER");
        assertThat(iv.getCancelReason()).isEqualTo("时间冲突");
        verify(reminderService).cancel(10L);
        org.mockito.ArgumentCaptor<com.ll.hirehub.common.mq.MqPayload.InterviewEvent> captor =
                org.mockito.ArgumentCaptor.forClass(com.ll.hirehub.common.mq.MqPayload.InterviewEvent.class);
        verify(localMessageRecorder).record(eq(MqConst.BizType.INTERVIEW_EVENT), eq(10L),
                eq(MqConst.RK_INTERVIEW_EVENT), captor.capture());
        assertThat(captor.getValue().getEvent()).isEqualTo("CANCELLED");
        assertThat(captor.getValue().getReceiverIds()).containsExactly(HR);
    }

    @Test
    @DisplayName("reschedule：HR 改期 → 回 SCHEDULED 待重新确认，并重排提醒（旧 token 失效）")
    void hrReschedulesBackToScheduled() {
        Interview iv = interviewOf(InterviewStatus.CONFIRMED.name());
        when(interviewMapper.selectById(10L)).thenReturn(iv);
        when(companyClient.getMember(COMPANY, HR)).thenReturn(Result.ok(hrMember()));
        RescheduleRequest req = new RescheduleRequest();
        LocalDateTime newTime = LocalDateTime.now().plusDays(5);
        req.setInterviewTime(newTime);

        service.reschedule(HR, 10L, req);

        assertThat(iv.getStatus()).isEqualTo(InterviewStatus.SCHEDULED.name());
        assertThat(iv.getInterviewTime()).isEqualTo(newTime);
        verify(reminderService).schedule(iv);
    }

    @Test
    @DisplayName("reschedule：求职者不能改期 → 403")
    void seekerCannotReschedule() {
        Interview iv = interviewOf(InterviewStatus.CONFIRMED.name());
        when(interviewMapper.selectById(10L)).thenReturn(iv);
        RescheduleRequest req = new RescheduleRequest();
        req.setInterviewTime(LocalDateTime.now().plusDays(5));

        assertThatThrownBy(() -> service.reschedule(SEEKER, 10L, req))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));
    }

    @Test
    @DisplayName("feedback：HR 完成面试 → COMPLETED + 反馈落库 + 清理提醒")
    void feedbackCompletesInterview() {
        Interview iv = interviewOf(InterviewStatus.CONFIRMED.name());
        when(interviewMapper.selectById(10L)).thenReturn(iv);
        when(companyClient.getMember(COMPANY, HR)).thenReturn(Result.ok(hrMember()));
        FeedbackRequest req = new FeedbackRequest();
        req.setResult("PASS");
        req.setComment("基础扎实");

        service.feedback(HR, 10L, req);

        assertThat(iv.getStatus()).isEqualTo(InterviewStatus.COMPLETED.name());
        ArgumentCaptor<InterviewFeedback> captor = ArgumentCaptor.forClass(InterviewFeedback.class);
        verify(feedbackMapper).insert(captor.capture());
        assertThat(captor.getValue().getResult()).isEqualTo("PASS");
        assertThat(captor.getValue().getRecorderId()).isEqualTo(HR);
        verify(reminderService).cancel(10L);
    }

    @Test
    @DisplayName("transition：终态面试（COMPLETED）任何操作 → 非法状态迁移")
    void terminalInterviewRejectsEverything() {
        Interview iv = interviewOf(InterviewStatus.COMPLETED.name());
        when(interviewMapper.selectById(10L)).thenReturn(iv);
        when(companyClient.getMember(COMPANY, HR)).thenReturn(Result.ok(hrMember()));

        assertThatThrownBy(() -> service.cancel(HR, 10L, new CancelRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法状态迁移");
    }
}
