package com.ll.hirehub.job.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ll.hirehub.api.AuthClient;
import com.ll.hirehub.api.CompanyClient;
import com.ll.hirehub.api.dto.CompanyMemberDTO;
import com.ll.hirehub.common.exception.BusinessException;
import com.ll.hirehub.common.result.ResultCode;
import com.ll.hirehub.job.dto.CreateJobRequest;
import com.ll.hirehub.job.cache.BloomFilter;
import com.ll.hirehub.job.cache.JobCacheService;
import com.ll.hirehub.job.entity.Job;
import com.ll.hirehub.job.mapper.JobMapper;
import com.ll.hirehub.job.search.JobSyncEvent;
import com.ll.hirehub.job.vo.JobPageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

    private final JobMapper jobMapper;
    private final CompanyClient companyClient;
    private final AuthClient authClient;
    private final JobCacheService jobCacheService;
    private final BloomFilter bloomFilter;
    private final ApplicationEventPublisher eventPublisher;

    /** 创建职位草稿（需实名 + 归属正常，见 §7） */
    @Transactional(rollbackFor = Exception.class)
    public Long draft(Long userId, CreateJobRequest req) {
        // 审计 F-10：§7 接口契约要求 draft/offline/delete 同样校验实名，此前只有 publish 校验
        requireRealName(userId);
        requireMembership(req.getCompanyId(), userId);

        Job job = new Job();
        job.setCompanyId(req.getCompanyId());
        job.setPublisherId(userId);
        job.setTitle(req.getTitle());
        job.setCategoryId(req.getCategoryId());
        job.setSkills(req.getSkills());
        job.setCity(req.getCity());
        job.setDistrict(req.getDistrict());
        job.setAddress(req.getAddress());
        job.setRemote(req.getRemote());
        job.setSalaryMin(req.getSalaryMin());
        job.setSalaryMax(req.getSalaryMax());
        job.setSalaryType(req.getSalaryType());
        job.setNegotiable(req.getNegotiable());
        job.setEducation(req.getEducation());
        job.setExperience(req.getExperience());
        job.setHeadcount(req.getHeadcount());
        job.setJobType(req.getJobType());
        job.setDescription(req.getDescription());
        job.setRequirement(req.getRequirement());
        job.setStatus(0); // 草稿
        job.setDeliveryCount(0);
        job.setViewCount(0);
        jobMapper.insert(job);
        try {
            bloomFilter.add(job.getId());   // 新职位同步进布隆，保证 mightContain 不漏报
        } catch (Exception e) {
            // 审计 Q-06：缓存只是加速层，Redis 故障不应阻断建单（对账/重建会补齐）
            log.warn("布隆过滤器写入失败（不影响建单，等待重建补齐）: jobId={}, err={}", job.getId(), e.getMessage());
        }
        return job.getId();
    }

    /** 上线职位 ★ 三重校验：实名 + 归属 + 企业已认证（见 §6.7 / D-15） */
    @Transactional(rollbackFor = Exception.class)
    public void publish(Long userId, Long jobId) {
        Job job = requireJob(jobId);

        // ① 实名
        requireRealName(userId);
        // ② 归属
        requireMembership(job.getCompanyId(), userId);
        // ③ 企业已认证
        Integer verifyStatus = companyClient.getVerifyStatus(job.getCompanyId()).getData();
        if (verifyStatus == null || verifyStatus != 1) {
            throw new BusinessException("企业未通过认证，无法上线职位");
        }

        if (job.getStatus() == null || (job.getStatus() != 0 && job.getStatus() != 2)) {
            throw new BusinessException("仅草稿或已下线状态可上线");
        }
        // 审计 F-01：已下线（含企业认证失效被强制下线）允许 HR 手动重新上线（Q-08 设计）
        boolean republish = job.getStatus() == 2;
        job.setStatus(1); // 招聘中
        job.setPublishTime(LocalDateTime.now());
        jobMapper.updateById(job);
        if (republish) {
            // updateById 默认忽略 null 字段，清 offlineReason 必须显式 set null
            jobMapper.update(null, new LambdaUpdateWrapper<Job>()
                    .eq(Job::getId, jobId)
                    .set(Job::getOfflineReason, null));
        }
        jobCacheService.evict(jobId);
        eventPublisher.publishEvent(new JobSyncEvent(jobId, false));   // 同步 job_index
    }

    /** 下线职位（HR 主动，见 §7） */
    @Transactional(rollbackFor = Exception.class)
    public void offline(Long userId, Long jobId) {
        Job job = requireJob(jobId);
        requireRealName(userId);   // 审计 F-10
        requireMembership(job.getCompanyId(), userId);
        if (job.getStatus() == null || job.getStatus() != 1) {
            throw new BusinessException("仅招聘中的职位可下线");
        }
        job.setStatus(2); // 已下线
        job.setOfflineReason("手动");
        jobMapper.updateById(job);
        jobCacheService.evict(jobId);
        eventPublisher.publishEvent(new JobSyncEvent(jobId, false));   // 状态变化 → 同步索引（status=2 会被搜索过滤掉）
    }

    /** 删除职位：仅 OWNER（见 D-21） */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long jobId) {
        Job job = requireJob(jobId);
        requireRealName(userId);   // 审计 F-10
        CompanyMemberDTO member = requireMembership(job.getCompanyId(), userId);
        if (!"OWNER".equals(member.getRole())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "仅企业 OWNER 可删除职位");
        }
        jobMapper.deleteById(jobId); // 逻辑删除
        jobCacheService.evict(jobId);
        eventPublisher.publishEvent(new JobSyncEvent(jobId, true));    // 从 job_index 移除
    }

    /** 对外读走缓存（缓存三防）；内部 Feign 读仍走 DB，见 JobCacheService 注释 */
    public Job get(Long jobId) {
        Job cached = jobCacheService.getCached(jobId);
        if (cached == null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        // 审计 F-17①：浏览计数——SQL 自增避免并发丢更新；失败不影响阅读（计数非关键路径）。
        // 缓存副本的 viewCount 会短暂滞后，统计口径以 DB 为准（JobStatisticsVO 走 SQL 聚合）
        try {
            jobMapper.update(null, new LambdaUpdateWrapper<Job>()
                    .eq(Job::getId, jobId)
                    .setSql("view_count = view_count + 1"));
        } catch (Exception e) {
            log.warn("职位浏览计数自增失败（不影响详情返回）: jobId={}, err={}", jobId, e.getMessage());
        }
        return cached;
    }

    /**
     * HR 视角：本企业的职位列表（**含草稿与已下线**，见 D-41）。
     * <p>
     * <b>为什么不复用 /search</b>：{@code job_index} 只索引 {@code status=1} 的职位，
     * 草稿与已下线的职位根本不在索引里；/search 也不支持按企业过滤。
     * 这个接口要的是"我发布过的全部职位"，而不是"能被别人搜到的职位"，所以直接走 MySQL。
     * <p>
     * 归属校验复用 {@link #requireMembership}：非该企业在职 OWNER/HR 一律 403。
     */
    public JobPageVO mine(Long companyId, Long userId, Integer status, int page, int size) {
        requireMembership(companyId, userId);
        long current = Math.max(page, 1);
        long limit = Math.min(Math.max(size, 1), 100);   // 封顶，避免一次把全表拉出来
        Page<Job> result = jobMapper.selectPage(new Page<>(current, limit),
                new LambdaQueryWrapper<Job>()
                        .eq(Job::getCompanyId, companyId)
                        .eq(status != null, Job::getStatus, status)
                        .orderByDesc(Job::getUpdateTime));
        JobPageVO vo = new JobPageVO();
        vo.setTotal(result.getTotal());
        vo.setJobs(result.getRecords());
        return vo;
    }

    private CompanyMemberDTO requireMembership(Long companyId, Long userId) {        CompanyMemberDTO member = companyClient.getMember(companyId, userId).getData();
        if (member == null || member.getStatus() == null || member.getStatus() != 1) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "非该企业在职成员");
        }
        if (!"OWNER".equals(member.getRole()) && !"HR".equals(member.getRole())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无职位操作权限");
        }
        return member;
    }

    /** 实名认证门（审计 F-10）：§7 对 draft/publish/offline/delete 均要求"[OWNER/HR] 实名" */
    private void requireRealName(Long userId) {
        Integer realNameStatus = authClient.getRealNameStatus(userId).getData();
        if (realNameStatus == null || realNameStatus != 1) {
            throw new BusinessException("请先完成实名认证");
        }
    }

    private Job requireJob(Long id) {
        Job job = jobMapper.selectById(id);
        if (job == null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        return job;
    }
}
