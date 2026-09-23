package com.ll.hirehub.resume.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ll.hirehub.common.exception.BusinessException;
import com.ll.hirehub.common.result.ResultCode;
import com.ll.hirehub.resume.dto.SavePreferenceRequest;
import com.ll.hirehub.resume.dto.SaveResumeRequest;
import com.ll.hirehub.resume.entity.JobPreference;
import com.ll.hirehub.resume.entity.Resume;
import com.ll.hirehub.resume.mapper.JobPreferenceMapper;
import com.ll.hirehub.resume.mapper.ResumeMapper;
import com.ll.hirehub.resume.search.ResumeIndexService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ResumeService {

    private final ResumeMapper resumeMapper;
    private final JobPreferenceMapper preferenceMapper;
    private final ResumeIndexService resumeIndexService;

    @Transactional(rollbackFor = Exception.class)
    public Long create(Long userId, SaveResumeRequest req) {
        Resume resume = new Resume();
        resume.setUserId(userId);
        applyReq(resume, req);
        resume.setParseStatus(0);
        resumeMapper.insert(resume);
        // 新建后立刻按隐私规则评估：默认最小可见（多半不该进索引，refresh 会自行判断）
        // 用 afterCommit 版本：ES 不参与数据库回滚，写在提交前会产生"库里没有、索引里有"的漂移
        resumeIndexService.refreshAfterCommit(resume.getId());
        return resume.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long userId, Long resumeId, SaveResumeRequest req) {
        Resume resume = requireOwner(userId, resumeId);
        applyReq(resume, req);
        resumeMapper.updateById(resume);
        // status（公开/保密）在这里可能被改动 → 必须同步索引，否则"关了曝光还能被搜到"
        resumeIndexService.refreshAfterCommit(resumeId);
    }

    public Resume get(Long userId, Long resumeId) {
        return requireOwner(userId, resumeId);
    }

    public List<Resume> mine(Long userId) {
        return resumeMapper.selectList(
                new LambdaQueryWrapper<Resume>().eq(Resume::getUserId, userId));
    }

    public JobPreference getPreference(Long userId) {
        return preferenceMapper.selectOne(
                new LambdaQueryWrapper<JobPreference>().eq(JobPreference::getUserId, userId));
    }

    @Transactional(rollbackFor = Exception.class)
    public void savePreference(Long userId, SavePreferenceRequest req) {
        JobPreference p = preferenceMapper.selectOne(
                new LambdaQueryWrapper<JobPreference>().eq(JobPreference::getUserId, userId));
        if (p == null) {
            p = new JobPreference();
            p.setUserId(userId);
            applyPreference(p, req);
            preferenceMapper.insert(p);
        } else {
            applyPreference(p, req);
            preferenceMapper.updateById(p);
        }
        // 求职状态 / 屏蔽公司变化会改变全部简历的可搜性（D-23 要求实时生效，不能等对账）
        resumeIndexService.refreshByUserAfterCommit(userId);
    }

    private Resume requireOwner(Long userId, Long resumeId) {
        Resume resume = resumeMapper.selectById(resumeId);
        if (resume == null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        if (!resume.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return resume;
    }

    private void applyReq(Resume resume, SaveResumeRequest req) {
        resume.setTitle(req.getTitle());
        resume.setName(req.getName());
        resume.setGender(req.getGender());
        resume.setBirth(req.getBirth());
        resume.setPhone(req.getPhone());
        resume.setEmail(req.getEmail());
        resume.setExpectCity(req.getExpectCity());
        resume.setExpectSalaryMin(req.getExpectSalaryMin());
        resume.setExpectSalaryMax(req.getExpectSalaryMax());
        resume.setExpectPosition(req.getExpectPosition());
        if (req.getStatus() != null) {
            resume.setStatus(req.getStatus());
        }
    }

    private void applyPreference(JobPreference p, SavePreferenceRequest req) {
        p.setJobStatus(req.getJobStatus());
        p.setExpectCategoryIds(req.getExpectCategoryIds());
        p.setExpectCity(req.getExpectCity());
        p.setExpectSalaryMin(req.getExpectSalaryMin());
        p.setExpectSalaryMax(req.getExpectSalaryMax());
        p.setBlockedCompanyIds(req.getBlockedCompanyIds());
    }
}
