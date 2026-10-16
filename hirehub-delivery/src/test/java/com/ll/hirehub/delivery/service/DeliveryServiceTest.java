package com.ll.hirehub.delivery.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ll.hirehub.api.CompanyClient;
import com.ll.hirehub.api.JobClient;
import com.ll.hirehub.api.ResumeClient;
import com.ll.hirehub.api.dto.CompanyMemberDTO;
import com.ll.hirehub.api.dto.JobDTO;
import com.ll.hirehub.api.dto.ResumeDTO;
import com.ll.hirehub.common.exception.BusinessException;
import com.ll.hirehub.common.mq.MqConst;
import com.ll.hirehub.common.mq.MqPayload;
import com.ll.hirehub.common.result.Result;
import com.ll.hirehub.common.result.ResultCode;
import com.ll.hirehub.delivery.dto.ApplyRequest;
import com.ll.hirehub.delivery.entity.Delivery;
import com.ll.hirehub.delivery.entity.DeliveryResume;
import com.ll.hirehub.delivery.enums.DeliveryEvent;
import com.ll.hirehub.delivery.enums.DeliveryStatus;
import com.ll.hirehub.delivery.mapper.DeliveryMapper;
import com.ll.hirehub.delivery.mapper.DeliveryResumeMapper;
import com.ll.hirehub.delivery.mq.LocalMessageRecorder;
import com.ll.hirehub.delivery.statemachine.DeliveryStateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 投递服务（D-26/27/28 + 本地消息表）单元测试。
 * 覆盖：幂等创建、职位/简历校验、HR 自动 VIEW、乐观锁迁移、通知消息落库、越权。
 * 状态机本体由 {@link DeliveryStateMachineTest} 覆盖，这里使用真实状态机串联。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeliveryServiceTest {

    @Mock
    private DeliveryMapper deliveryMapper;
    @Mock
    private DeliveryResumeMapper resumeMapper;
    @Mock
    private JobClient jobClient;
    @Mock
    private ResumeClient resumeClient;
    @Mock
    private CompanyClient companyClient;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private LocalMessageRecorder localMessageRecorder;

    private DeliveryService service;

    private static final Long SEEKER = 5L;
    private static final Long HR = 7L;
    private static final Long COMPANY = 9L;

    @BeforeEach
    void setUp() {
        // 纯单元测试没有 MyBatis 上下文，LambdaUpdateWrapper 解析列名需要实体 TableInfo，手动初始化
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Delivery.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new DeliveryService(deliveryMapper, resumeMapper, new DeliveryStateMachine(),
                jobClient, resumeClient, companyClient, new ObjectMapper(),
                redisTemplate, localMessageRecorder);
    }

    private ApplyRequest applyRequest() {
        ApplyRequest req = new ApplyRequest();
        req.setJobId(100L);
        req.setResumeId(200L);
        return req;
    }

    private JobDTO onlineJob() {
        JobDTO job = new JobDTO();
        job.setId(100L);
        job.setCompanyId(COMPANY);
        job.setTitle("Java 后端");
        job.setStatus(1);
        return job;
    }

    private ResumeDTO ownedResume() {
        ResumeDTO resume = new ResumeDTO();
        resume.setId(200L);
        resume.setUserId(SEEKER);
        resume.setName("张三");
        return resume;
    }

    private CompanyMemberDTO hrMember() {
        CompanyMemberDTO member = new CompanyMemberDTO();
        member.setCompanyId(COMPANY);
        member.setUserId(HR);
        member.setRole("HR");
        member.setStatus(1);
        return member;
    }

    private Delivery deliveryOf(Long seekerId, String status) {
        Delivery d = new Delivery();
        d.setId(1L);
        d.setJobId(100L);
        d.setResumeId(200L);
        d.setSeekerId(seekerId);
        d.setCompanyId(COMPANY);
        d.setStatus(status);
        return d;
    }

    // -------------------- apply：幂等与校验 --------------------

    @Test
    @DisplayName("apply：已存在同 (job,resume) 投递 → 幂等返回原 id，不再落库")
    void applyIdempotentPrecheck() {
        Delivery exist = deliveryOf(SEEKER, DeliveryStatus.PENDING.name());
        when(deliveryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(exist);

        Long id = service.apply(SEEKER, applyRequest());

        assertThat(id).isEqualTo(1L);
        verify(deliveryMapper, never()).insert(any(Delivery.class));
        verifyNoInteractions(jobClient, localMessageRecorder);
    }

    @Test
    @DisplayName("apply：职位不在招聘中 → 拒绝")
    void applyRejectsOfflineJob() {
        JobDTO job = onlineJob();
        job.setStatus(2);
        when(jobClient.getJob(100L)).thenReturn(Result.ok(job));

        assertThatThrownBy(() -> service.apply(SEEKER, applyRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("职位不存在或不在招聘中");
        verify(deliveryMapper, never()).insert(any(Delivery.class));
    }

    @Test
    @DisplayName("apply：简历不属于当前求职者 → 拒绝（防用他人简历投递）")
    void applyRejectsOthersResume() {
        when(jobClient.getJob(100L)).thenReturn(Result.ok(onlineJob()));
        ResumeDTO resume = ownedResume();
        resume.setUserId(999L);
        when(resumeClient.getResume(200L)).thenReturn(Result.ok(resume));

        assertThatThrownBy(() -> service.apply(SEEKER, applyRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("简历不存在或无权使用");
        verify(deliveryMapper, never()).insert(any(Delivery.class));
    }

    @Test
    @DisplayName("apply：成功 → 投递 + 简历快照 + 本地消息（通知 HR）同一流程完成")
    void applySuccessWritesSnapshotAndLocalMessage() {
        when(jobClient.getJob(100L)).thenReturn(Result.ok(onlineJob()));
        when(resumeClient.getResume(200L)).thenReturn(Result.ok(ownedResume()));
        doAnswer(inv -> {
            inv.<Delivery>getArgument(0).setId(300L);
            return 1;
        }).when(deliveryMapper).insert(any(Delivery.class));
        when(companyClient.listMemberUserIds(COMPANY)).thenReturn(Result.ok(List.of(HR)));

        Long id = service.apply(SEEKER, applyRequest());

        assertThat(id).isEqualTo(300L);
        verify(resumeMapper).insert(any(DeliveryResume.class));

        ArgumentCaptor<MqPayload.DeliveryCreated> captor =
                ArgumentCaptor.forClass(MqPayload.DeliveryCreated.class);
        verify(localMessageRecorder).record(eq(MqConst.BizType.DELIVERY_CREATED), eq(300L),
                eq(MqConst.RK_DELIVERY_CREATED), captor.capture());
        MqPayload.DeliveryCreated payload = captor.getValue();
        assertThat(payload.getReceiverIds()).containsExactly(HR);
        assertThat(payload.getSeekerId()).isEqualTo(SEEKER);
        assertThat(payload.getJobTitle()).isEqualTo("Java 后端");
    }

    @Test
    @DisplayName("apply：并发撞库（uk 冲突）→ 由 DB 兜底返回已存在投递，不报错")
    void applyDuplicateKeyFallsBackToExisting() {
        when(jobClient.getJob(100L)).thenReturn(Result.ok(onlineJob()));
        when(resumeClient.getResume(200L)).thenReturn(Result.ok(ownedResume()));
        Delivery dup = deliveryOf(SEEKER, DeliveryStatus.PENDING.name());
        dup.setId(400L);
        when(deliveryMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(null)   // 幂等预检未命中
                .thenReturn(dup);   // uk 冲突后的回查命中
        when(deliveryMapper.insert(any(Delivery.class)))
                .thenThrow(new DuplicateKeyException("uk_job_resume"));

        Long id = service.apply(SEEKER, applyRequest());

        assertThat(id).isEqualTo(400L);
        verifyNoInteractions(localMessageRecorder);
    }

    @Test
    @DisplayName("apply：幂等预检命中他人投递 → 应拒绝而非返回他人投递 id（审计 S-13 回归）")
    void applyPrecheckShouldNotLeakOthersDelivery() {
        Delivery others = deliveryOf(999L, DeliveryStatus.PENDING.name());
        when(deliveryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(others);

        assertThatThrownBy(() -> service.apply(SEEKER, applyRequest()))
                .isInstanceOf(BusinessException.class);
    }

    // -------------------- get：HR 自动 VIEW --------------------

    @Test
    @DisplayName("get：HR 打开 PENDING 投递 → 条件更新置 VIEWED（WHERE status=PENDING，防并发 CANCEL 互踩）")
    void hrViewTransitionsToViewed() {
        Delivery d = deliveryOf(SEEKER, DeliveryStatus.PENDING.name());
        when(deliveryMapper.selectById(1L)).thenReturn(d);
        when(companyClient.getMember(COMPANY, HR)).thenReturn(Result.ok(hrMember()));
        when(deliveryMapper.update(any(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(1);

        Delivery result = service.get(HR, 1L);

        assertThat(result.getStatus()).isEqualTo(DeliveryStatus.VIEWED.name());
        verify(deliveryMapper).update(any(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verify(deliveryMapper, never()).updateById(any(Delivery.class));
    }

    @Test
    @DisplayName("get：求职者本人查看 → 不触发状态变更")
    void seekerViewDoesNotChangeStatus() {
        Delivery d = deliveryOf(SEEKER, DeliveryStatus.PENDING.name());
        when(deliveryMapper.selectById(1L)).thenReturn(d);

        Delivery result = service.get(SEEKER, 1L);

        assertThat(result.getStatus()).isEqualTo(DeliveryStatus.PENDING.name());
        verify(deliveryMapper, never()).updateById(any(Delivery.class));
    }

    @Test
    @DisplayName("get：既非求职者也非企业成员 → 403")
    void outsiderForbidden() {
        Delivery d = deliveryOf(SEEKER, DeliveryStatus.PENDING.name());
        when(deliveryMapper.selectById(1L)).thenReturn(d);
        when(companyClient.getMember(eq(COMPANY), anyLong())).thenReturn(Result.ok(null));

        assertThatThrownBy(() -> service.get(12345L, 1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));
    }

    // -------------------- applyEvent：乐观锁与通知 --------------------

    @Test
    @DisplayName("applyEvent：乐观锁冲突（affected=0）→ 报「状态已被他人修改」")
    void applyEventOptimisticLockConflict() {
        Delivery d = deliveryOf(SEEKER, DeliveryStatus.COMMUNICATING.name());
        when(deliveryMapper.selectById(1L)).thenReturn(d);
        when(companyClient.getMember(COMPANY, HR)).thenReturn(Result.ok(hrMember()));
        when(deliveryMapper.update(any(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(0);

        assertThatThrownBy(() -> service.applyEvent(HR, 1L, DeliveryEvent.REJECT))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("状态已被他人修改");
        verifyNoInteractions(localMessageRecorder);
    }

    @Test
    @DisplayName("applyEvent：HR 拒绝（COMMUNICATING → REJECTED）→ 同事务记录通知求职者的消息")
    void applyEventRejectNotifiesSeeker() {
        Delivery d = deliveryOf(SEEKER, DeliveryStatus.COMMUNICATING.name());
        when(deliveryMapper.selectById(1L)).thenReturn(d);
        when(companyClient.getMember(COMPANY, HR)).thenReturn(Result.ok(hrMember()));
        when(deliveryMapper.update(any(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(1);
        when(jobClient.getJob(100L)).thenReturn(Result.ok(onlineJob()));

        service.applyEvent(HR, 1L, DeliveryEvent.REJECT);

        ArgumentCaptor<MqPayload.DeliveryEvent> captor =
                ArgumentCaptor.forClass(MqPayload.DeliveryEvent.class);
        verify(localMessageRecorder).record(eq(MqConst.BizType.DELIVERY_EVENT), eq(1L),
                eq(MqConst.RK_DELIVERY_EVENT), captor.capture());
        MqPayload.DeliveryEvent payload = captor.getValue();
        assertThat(payload.getEvent()).isEqualTo("REJECT");
        assertThat(payload.getNotifyTo()).isEqualTo("SEEKER");
        assertThat(payload.getReceiverIds()).containsExactly(SEEKER);
        assertThat(payload.getFromStatus()).isEqualTo("COMMUNICATING");
        assertThat(payload.getToStatus()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("applyEvent：VIEW 事件 → 只迁移状态，不产生通知消息（§6.2 通知映射）")
    void applyEventViewDoesNotNotify() {
        Delivery d = deliveryOf(SEEKER, DeliveryStatus.PENDING.name());
        when(deliveryMapper.selectById(1L)).thenReturn(d);
        when(companyClient.getMember(COMPANY, HR)).thenReturn(Result.ok(hrMember()));
        when(deliveryMapper.update(any(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(1);

        service.applyEvent(HR, 1L, DeliveryEvent.VIEW);

        verify(localMessageRecorder, never()).record(anyString(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("applyEvent：求职者取消 → 通知企业全体成员（HR 接收人由生产端解析）")
    void applyEventCancelNotifiesHrMembers() {
        Delivery d = deliveryOf(SEEKER, DeliveryStatus.PENDING.name());
        when(deliveryMapper.selectById(1L)).thenReturn(d);
        when(deliveryMapper.update(any(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(1);
        when(companyClient.listMemberUserIds(COMPANY)).thenReturn(Result.ok(List.of(HR, 8L)));
        when(jobClient.getJob(100L)).thenReturn(Result.ok(onlineJob()));

        service.applyEvent(SEEKER, 1L, DeliveryEvent.CANCEL);

        ArgumentCaptor<MqPayload.DeliveryEvent> captor =
                ArgumentCaptor.forClass(MqPayload.DeliveryEvent.class);
        verify(localMessageRecorder).record(eq(MqConst.BizType.DELIVERY_EVENT), eq(1L),
                eq(MqConst.RK_DELIVERY_EVENT), captor.capture());
        assertThat(captor.getValue().getNotifyTo()).isEqualTo("HR");
        assertThat(captor.getValue().getReceiverIds()).containsExactly(HR, 8L);
    }

    @Test
    @DisplayName("applyEvent：求职者发起 HR 专属事件 → 状态机 403 拦截")
    void applyEventSeekerCannotAdvanceFunnel() {
        Delivery d = deliveryOf(SEEKER, DeliveryStatus.PENDING.name());
        when(deliveryMapper.selectById(1L)).thenReturn(d);

        assertThatThrownBy(() -> service.applyEvent(SEEKER, 1L, DeliveryEvent.CONTACT))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));
        verify(deliveryMapper, never()).update(any(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }
}
