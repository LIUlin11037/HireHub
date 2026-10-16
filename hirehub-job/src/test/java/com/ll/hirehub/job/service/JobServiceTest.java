package com.ll.hirehub.job.service;

import com.ll.hirehub.api.AuthClient;
import com.ll.hirehub.api.CompanyClient;
import com.ll.hirehub.api.dto.CompanyMemberDTO;
import com.ll.hirehub.common.exception.BusinessException;
import com.ll.hirehub.common.result.Result;
import com.ll.hirehub.common.result.ResultCode;
import com.ll.hirehub.job.cache.BloomFilter;
import com.ll.hirehub.job.cache.JobCacheService;
import com.ll.hirehub.job.dto.CreateJobRequest;
import com.ll.hirehub.job.entity.Job;
import com.ll.hirehub.job.mapper.JobMapper;
import com.ll.hirehub.job.search.JobSyncEvent;
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
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 职位服务（§6.7 / §7 / D-15 / D-21）单元测试。
 * 覆盖：草稿/上线三重校验（实名+归属+企业认证）、下线、OWNER 专属删除、
 * 缓存失效与 ES 同步事件。状态含义：0 草稿 / 1 招聘中 / 2 已下线。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JobServiceTest {

    @Mock
    private JobMapper jobMapper;
    @Mock
    private CompanyClient companyClient;
    @Mock
    private AuthClient authClient;
    @Mock
    private JobCacheService jobCacheService;
    @Mock
    private BloomFilter bloomFilter;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private JobService service;

    private static final Long USER = 7L;
    private static final Long COMPANY = 9L;

    @BeforeEach
    void setUp() {
        // 纯单元测试没有 MyBatis 上下文，LambdaUpdateWrapper 解析列名需要实体 TableInfo，手动初始化
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), Job.class);
        service = new JobService(jobMapper, companyClient, authClient, jobCacheService,
                bloomFilter, eventPublisher);
        // 审计 F-10：draft/offline/delete 也走实名校验——默认桩为"已实名"，
        // 个别用例（publishRequiresRealName / draftRequiresRealName）在测试体内覆盖为未实名
        lenient().when(authClient.getRealNameStatus(USER)).thenReturn(Result.ok(1));
    }

    private CompanyMemberDTO memberOf(String role) {
        CompanyMemberDTO m = new CompanyMemberDTO();
        m.setCompanyId(COMPANY);
        m.setUserId(USER);
        m.setRole(role);
        m.setStatus(1);
        return m;
    }

    private Job jobOf(int status) {
        Job job = new Job();
        job.setId(1L);
        job.setCompanyId(COMPANY);
        job.setStatus(status);
        return job;
    }

    private void stubMembershipOk() {
        when(companyClient.getMember(COMPANY, USER)).thenReturn(Result.ok(memberOf("HR")));
    }

    // -------------------- draft --------------------

    @Test
    @DisplayName("draft：企业在职 HR → 创建草稿并同步布隆过滤器")
    void draftSuccess() {
        stubMembershipOk();
        doAnswer(inv -> {
            inv.<Job>getArgument(0).setId(10L);
            return 1;
        }).when(jobMapper).insert(any(Job.class));
        CreateJobRequest req = new CreateJobRequest();
        req.setCompanyId(COMPANY);
        req.setTitle("Java 后端工程师");

        Long id = service.draft(USER, req);

        assertThat(id).isEqualTo(10L);
        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(0);
        assertThat(captor.getValue().getPublisherId()).isEqualTo(USER);
        verify(bloomFilter).add(10L);
    }

    @Test
    @DisplayName("draft：非企业在职成员 → 403，不落库")
    void draftForbiddenForNonMember() {
        when(companyClient.getMember(COMPANY, USER)).thenReturn(Result.ok(null));
        CreateJobRequest req = new CreateJobRequest();
        req.setCompanyId(COMPANY);

        assertThatThrownBy(() -> service.draft(USER, req))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));
        verify(jobMapper, never()).insert(any(Job.class));
    }

    @Test
    @DisplayName("draft：未实名 → 拒绝（审计 F-10，§7 契约要求 draft 也校验实名）")
    void draftRequiresRealName() {
        when(authClient.getRealNameStatus(USER)).thenReturn(Result.ok(0));
        CreateJobRequest req = new CreateJobRequest();
        req.setCompanyId(COMPANY);

        assertThatThrownBy(() -> service.draft(USER, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请先完成实名认证");
        verify(jobMapper, never()).insert(any(Job.class));
    }

    // -------------------- publish：三重校验 --------------------

    @Test
    @DisplayName("publish：实名+归属+企业已认证 → 草稿上线，失效缓存并同步索引")
    void publishSuccess() {
        when(jobMapper.selectById(1L)).thenReturn(jobOf(0));
        when(authClient.getRealNameStatus(USER)).thenReturn(Result.ok(1));
        stubMembershipOk();
        when(companyClient.getVerifyStatus(COMPANY)).thenReturn(Result.ok(1));

        service.publish(USER, 1L);

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(1);
        assertThat(captor.getValue().getPublishTime()).isNotNull();
        verify(jobCacheService).evict(1L);
        ArgumentCaptor<JobSyncEvent> event = ArgumentCaptor.forClass(JobSyncEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().isDelete()).isFalse();
    }

    @Test
    @DisplayName("publish：未实名 → 拒绝（三重校验第①关，D-15）")
    void publishRequiresRealName() {
        when(jobMapper.selectById(1L)).thenReturn(jobOf(0));
        when(authClient.getRealNameStatus(USER)).thenReturn(Result.ok(0));

        assertThatThrownBy(() -> service.publish(USER, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请先完成实名认证");
        verify(jobMapper, never()).updateById(any(Job.class));
    }

    @Test
    @DisplayName("publish：企业未通过认证 → 拒绝（三重校验第③关，D-15）")
    void publishRequiresVerifiedCompany() {
        when(jobMapper.selectById(1L)).thenReturn(jobOf(0));
        when(authClient.getRealNameStatus(USER)).thenReturn(Result.ok(1));
        stubMembershipOk();
        when(companyClient.getVerifyStatus(COMPANY)).thenReturn(Result.ok(0));

        assertThatThrownBy(() -> service.publish(USER, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("企业未通过认证");
        verify(jobMapper, never()).updateById(any(Job.class));
    }

    @Test
    @DisplayName("publish：已下线职位重新上线（Q-08）→ 状态置 1、清空下线原因、失效缓存")
    void publishAllowsRepublishOfflineJob() {
        when(jobMapper.selectById(1L)).thenReturn(jobOf(2));
        when(authClient.getRealNameStatus(USER)).thenReturn(Result.ok(1));
        stubMembershipOk();
        when(companyClient.getVerifyStatus(COMPANY)).thenReturn(Result.ok(1));

        service.publish(USER, 1L);

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(1);
        verify(jobCacheService).evict(1L);
    }

    // -------------------- offline / delete --------------------

    @Test
    @DisplayName("offline：招聘中 → 已下线（原因=手动），失效缓存并同步索引")
    void offlineSuccess() {
        when(jobMapper.selectById(1L)).thenReturn(jobOf(1));
        stubMembershipOk();

        service.offline(USER, 1L);

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(2);
        assertThat(captor.getValue().getOfflineReason()).isEqualTo("手动");
        verify(jobCacheService).evict(1L);
        verify(eventPublisher).publishEvent(any(JobSyncEvent.class));
    }

    @Test
    @DisplayName("offline：草稿不能下线 → 拒绝")
    void offlineRejectsDraft() {
        when(jobMapper.selectById(1L)).thenReturn(jobOf(0));
        stubMembershipOk();

        assertThatThrownBy(() -> service.offline(USER, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅招聘中的职位可下线");
    }

    @Test
    @DisplayName("delete：HR 不可删，仅 OWNER 可删（D-21）")
    void deleteOnlyOwner() {
        when(jobMapper.selectById(1L)).thenReturn(jobOf(1));
        stubMembershipOk();   // 角色 HR

        assertThatThrownBy(() -> service.delete(USER, 1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));
        verify(jobMapper, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("delete：OWNER 删除 → 逻辑删除 + 缓存失效 + 从索引移除")
    void deleteByOwner() {
        when(jobMapper.selectById(1L)).thenReturn(jobOf(1));
        when(companyClient.getMember(COMPANY, USER)).thenReturn(Result.ok(memberOf("OWNER")));

        service.delete(USER, 1L);

        verify(jobMapper).deleteById(1L);
        verify(jobCacheService).evict(1L);
        ArgumentCaptor<JobSyncEvent> event = ArgumentCaptor.forClass(JobSyncEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().isDelete()).isTrue();
    }
}
