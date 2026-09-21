package com.ll.hirehub.job.service;

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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class JobService {

    private final JobMapper jobMapper;
    private final CompanyClient companyClient;
    private final AuthClient authClient;
    private final JobCacheService jobCacheService;
    private final BloomFilter bloomFilter;

    /** 创建职位草稿（需归属正常，见 §7） */
    @Transactional(rollbackFor = Exception.class)
    public Long draft(Long userId, CreateJobRequest req) {
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
        bloomFilter.add(job.getId());   // 新职位同步进布隆，保证 mightContain 不漏报
        return job.getId();
    }

    /** 上线职位 ★ 三重校验：实名 + 归属 + 企业已认证（见 §6.7 / D-15） */
    @Transactional(rollbackFor = Exception.class)
    public void publish(Long userId, Long jobId) {
        Job job = requireJob(jobId);

        // ① 实名
        Integer realNameStatus = authClient.getRealNameStatus(userId).getData();
        if (realNameStatus == null || realNameStatus != 1) {
            throw new BusinessException("请先完成实名认证");
        }
        // ② 归属
        requireMembership(job.getCompanyId(), userId);
        // ③ 企业已认证
        Integer verifyStatus = companyClient.getVerifyStatus(job.getCompanyId()).getData();
        if (verifyStatus == null || verifyStatus != 1) {
            throw new BusinessException("企业未通过认证，无法上线职位");
        }

        if (job.getStatus() == null || job.getStatus() != 0) {
            throw new BusinessException("仅草稿状态可上线");
        }
        job.setStatus(1); // 招聘中
        job.setPublishTime(LocalDateTime.now());
        jobMapper.updateById(job);
        jobCacheService.evict(jobId);
    }

    /** 下线职位（HR 主动，见 §7） */
    @Transactional(rollbackFor = Exception.class)
    public void offline(Long userId, Long jobId) {
        Job job = requireJob(jobId);
        requireMembership(job.getCompanyId(), userId);
        if (job.getStatus() == null || job.getStatus() != 1) {
            throw new BusinessException("仅招聘中的职位可下线");
        }
        job.setStatus(2); // 已下线
        job.setOfflineReason("手动");
        jobMapper.updateById(job);
        jobCacheService.evict(jobId);
    }

    /** 删除职位：仅 OWNER（见 D-21） */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long jobId) {
        Job job = requireJob(jobId);
        CompanyMemberDTO member = requireMembership(job.getCompanyId(), userId);
        if (!"OWNER".equals(member.getRole())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "仅企业 OWNER 可删除职位");
        }
        jobMapper.deleteById(jobId); // 逻辑删除
        jobCacheService.evict(jobId);
    }

    /** 对外读走缓存（缓存三防）；内部 Feign 读仍走 DB，见 JobCacheService 注释 */
    public Job get(Long jobId) {
        Job cached = jobCacheService.getCached(jobId);
        if (cached != null) {
            return cached;
        }
        throw new BusinessException(ResultCode.NOT_FOUND);
    }

    private CompanyMemberDTO requireMembership(Long companyId, Long userId) {
        CompanyMemberDTO member = companyClient.getMember(companyId, userId).getData();
        if (member == null || member.getStatus() == null || member.getStatus() != 1) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "非该企业在职成员");
        }
        if (!"OWNER".equals(member.getRole()) && !"HR".equals(member.getRole())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无职位操作权限");
        }
        return member;
    }

    private Job requireJob(Long id) {
        Job job = jobMapper.selectById(id);
        if (job == null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        return job;
    }
}
