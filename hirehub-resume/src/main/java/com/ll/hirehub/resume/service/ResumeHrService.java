package com.ll.hirehub.resume.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ll.hirehub.api.CompanyClient;
import com.ll.hirehub.api.dto.CompanyMemberDTO;
import com.ll.hirehub.common.exception.BusinessException;
import com.ll.hirehub.common.result.ResultCode;
import com.ll.hirehub.resume.entity.JobPreference;
import com.ll.hirehub.resume.entity.Resume;
import com.ll.hirehub.resume.entity.ResumeContactConsent;
import com.ll.hirehub.resume.entity.ResumeParseDetail;
import com.ll.hirehub.resume.entity.ResumeViewLog;
import com.ll.hirehub.resume.mapper.JobPreferenceMapper;
import com.ll.hirehub.resume.mapper.ResumeContactConsentMapper;
import com.ll.hirehub.resume.mapper.ResumeMapper;
import com.ll.hirehub.resume.mapper.ResumeParseDetailMapper;
import com.ll.hirehub.resume.mapper.ResumeViewLogMapper;
import com.ll.hirehub.resume.search.ResumeIndexService;
import com.ll.hirehub.resume.search.ResumeSearchService;
import com.ll.hirehub.resume.vo.ResumeHrDetailVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 人才库与隐私同意（见 D-23）。HR 侧与求职者侧的隐私入口都在这里。
 * <p>
 * 三条不可回退的规则：
 * <ol>
 *   <li><b>可见范围</b>：只有「已认证企业的在职成员」能搜人才库 / 看简历详情，否则 403。</li>
 *   <li><b>屏蔽公司</b>：被屏蔽企业的 HR 既搜不到、也打不开——<b>直接按 id 访问也要再查一次</b>，
 *       索引过滤只优化搜索路径，不能作为唯一防线。</li>
 *   <li><b>隐私命中一律返回 404</b>（不是 403）：403 等于告诉对方"这个人存在但不让你看"，
 *       会泄露"他屏蔽了你"这一事实本身。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeHrService {

    private final CompanyClient companyClient;
    private final ResumeMapper resumeMapper;
    private final JobPreferenceMapper preferenceMapper;
    private final ResumeParseDetailMapper detailMapper;
    private final ResumeContactConsentMapper consentMapper;
    private final ResumeViewLogMapper viewLogMapper;
    private final ResumeSearchService searchService;
    private final ResumeIndexService indexService;
    private final ResumePrivacyService privacy;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------ HR 侧

    /** 人才库检索：先过"已认证企业 HR"这道门，再进 ES（隐私过滤在查询里） */
    public ResumeSearchService.SearchResult search(Long userId, Long companyId, String keyword,
                                                   String city, String education, Integer salaryMin,
                                                   Integer minWorkYears, String searchAfter, int size) {
        requireVerifiedHr(userId, companyId);
        return searchService.search(companyId, keyword, city, education, salaryMin, minWorkYears,
                searchAfter, size);
    }

    /** HR 查看简历详情：匿名在前，联系方式需同意 */
    @Transactional(rollbackFor = Exception.class)
    public ResumeHrDetailVO detail(Long userId, Long companyId, Long resumeId) {
        requireVerifiedHr(userId, companyId);
        Resume resume = requireVisible(resumeId, companyId);
        JobPreference preference = preferenceOf(resume.getUserId());

        logView(resumeId, userId, companyId, "DETAIL");

        ResumeParseDetail parseDetail = detailMapper.selectOne(
                new LambdaQueryWrapper<ResumeParseDetail>().eq(ResumeParseDetail::getResumeId, resumeId));
        ResumeContactConsent consent = findConsent(resumeId, companyId);

        ResumeHrDetailVO vo = new ResumeHrDetailVO();
        vo.setResumeId(resumeId);
        vo.setAnonymousName(privacy.anonymousName(resume));
        vo.setExpectCity(resume.getExpectCity());
        vo.setExpectSalaryMin(resume.getExpectSalaryMin());
        vo.setExpectSalaryMax(resume.getExpectSalaryMax());
        vo.setExpectPosition(resume.getExpectPosition());
        vo.setJobStatus(preference == null ? null : preference.getJobStatus());
        vo.setParseStatus(resume.getParseStatus());
        vo.setConsentStatus(consent == null ? null : consent.getStatus());
        if (parseDetail != null) {
            vo.setSkills(parseSkills(parseDetail.getSkills()));
            vo.setEducation(parseDetail.getEducation());
            vo.setWorkYears(parseDetail.getWorkYears());
            vo.setWorkExperience(parseDetail.getWorkExperience());
        }

        // 只有"本企业已获同意"才下发真实信息
        boolean revealed = consent != null && consent.getStatus() != null
                && consent.getStatus() == ResumeContactConsent.STATUS_AGREED;
        vo.setContactRevealed(revealed);
        if (revealed) {
            vo.setName(resume.getName());
            vo.setPhone(resume.getPhone());
            vo.setEmail(resume.getEmail());
        }
        return vo;
    }

    /** HR 发起联系方式查看申请（幂等：已申请过不重复建，已同意的直接返回） */
    @Transactional(rollbackFor = Exception.class)
    public Long requestContact(Long userId, Long companyId, Long resumeId) {
        requireVerifiedHr(userId, companyId);
        requireVisible(resumeId, companyId);

        ResumeContactConsent existing = findConsent(resumeId, companyId);
        if (existing != null) {
            if (existing.getStatus() == ResumeContactConsent.STATUS_REJECTED) {
                throw new BusinessException("求职者已拒绝本企业的查看申请");
            }
            return existing.getId();
        }

        Resume resume = resumeMapper.selectById(resumeId);
        ResumeContactConsent consent = new ResumeContactConsent();
        consent.setResumeId(resumeId);
        consent.setSeekerUserId(resume.getUserId());
        consent.setCompanyId(companyId);
        consent.setHrUserId(userId);
        consent.setStatus(ResumeContactConsent.STATUS_PENDING);
        consent.setRequestTime(LocalDateTime.now());
        consentMapper.insert(consent);
        logView(resumeId, userId, companyId, "CONTACT");
        return consent.getId();
    }

    // -------------------------------------------------------------- 求职者侧

    /** 待我处理的联系方式申请 */
    public List<ResumeContactConsent> pendingConsents(Long userId) {
        return consentMapper.selectList(new LambdaQueryWrapper<ResumeContactConsent>()
                .eq(ResumeContactConsent::getSeekerUserId, userId)
                .orderByDesc(ResumeContactConsent::getId));
    }

    /** 求职者同意 / 拒绝。只有简历归属人本人可以操作。 */
    @Transactional(rollbackFor = Exception.class)
    public void consent(Long userId, Long consentId, boolean agree) {
        ResumeContactConsent consent = consentMapper.selectById(consentId);
        if (consent == null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        if (!consent.getSeekerUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "只能处理自己的简历授权");
        }
        if (consent.getStatus() != null && consent.getStatus() != ResumeContactConsent.STATUS_PENDING) {
            throw new BusinessException("该申请已处理过");
        }
        consent.setStatus(agree ? ResumeContactConsent.STATUS_AGREED
                : ResumeContactConsent.STATUS_REJECTED);
        consent.setConsentTime(LocalDateTime.now());
        consentMapper.updateById(consent);
    }

    /** 「谁看过我」——留痕对求职者可见，这是隐私模型的知情权部分 */
    public List<ResumeViewLog> viewLogs(Long userId, Long resumeId) {
        Resume resume = resumeMapper.selectById(resumeId);
        if (resume == null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        if (!resume.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return viewLogMapper.selectList(new LambdaQueryWrapper<ResumeViewLog>()
                .eq(ResumeViewLog::getResumeId, resumeId)
                .orderByDesc(ResumeViewLog::getId)
                .last("LIMIT 100"));
    }

    /** 解析结果（求职者本人可见，含失败原因） */
    public ResumeParseDetail parseResult(Long userId, Long resumeId) {
        Resume resume = resumeMapper.selectById(resumeId);
        if (resume == null || !resume.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        return detailMapper.selectOne(
                new LambdaQueryWrapper<ResumeParseDetail>().eq(ResumeParseDetail::getResumeId, resumeId));
    }

    /** 重建索引（求职者手动修复"改了状态但索引没跟上"的场景） */
    public void refreshIndex(Long userId, Long resumeId) {
        Resume resume = resumeMapper.selectById(resumeId);
        if (resume == null || !resume.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        indexService.refresh(resumeId);
    }

    // ------------------------------------------------------------------ 内部

    private void requireVerifiedHr(Long userId, Long companyId) {
        if (companyId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "缺少 X-Company-Id（人才库按企业维度授权）");
        }
        CompanyMemberDTO member = companyClient.getMember(companyId, userId).getData();
        if (member == null || member.getStatus() == null || member.getStatus() != 1) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "非该企业在职成员");
        }
        Integer verifyStatus = companyClient.getVerifyStatus(companyId).getData();
        if (verifyStatus == null || verifyStatus != 1) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "仅已认证企业可访问人才库");
        }
    }

    /** 隐私门：不可见 / 被屏蔽一律 404 */
    private Resume requireVisible(Long resumeId, Long companyId) {
        Resume resume = resumeMapper.selectById(resumeId);
        if (resume == null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        JobPreference preference = preferenceOf(resume.getUserId());
        if (!privacy.searchable(resume, preference)) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        if (privacy.blockedBy(preference, companyId)) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        return resume;
    }

    private JobPreference preferenceOf(Long userId) {
        return preferenceMapper.selectOne(
                new LambdaQueryWrapper<JobPreference>().eq(JobPreference::getUserId, userId));
    }

    private ResumeContactConsent findConsent(Long resumeId, Long companyId) {
        return consentMapper.selectOne(new LambdaQueryWrapper<ResumeContactConsent>()
                .eq(ResumeContactConsent::getResumeId, resumeId)
                .eq(ResumeContactConsent::getCompanyId, companyId));
    }

    private void logView(Long resumeId, Long viewerUserId, Long companyId, String scene) {
        ResumeViewLog logRow = new ResumeViewLog();
        logRow.setResumeId(resumeId);
        logRow.setViewerUserId(viewerUserId);
        logRow.setCompanyId(companyId);
        logRow.setScene(scene);
        logRow.setCreateTime(LocalDateTime.now());
        viewLogMapper.insert(logRow);
    }

    private List<String> parseSkills(String skillsJson) {
        if (skillsJson == null || skillsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(skillsJson, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
