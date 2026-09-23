package com.ll.hirehub.resume.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ll.hirehub.resume.entity.JobPreference;
import com.ll.hirehub.resume.entity.Resume;
import com.ll.hirehub.resume.entity.ResumeParseDetail;
import com.ll.hirehub.resume.search.ResumeDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 求职者隐私规则（见 D-23）。<b>所有可见性判断只走这一个类</b>。
 * <p>
 * 核心原则：<b>默认最小可见，由用户主动放开。</b>
 * <pre>
 *   可被 HR 搜到  ⟺  job_status ∈ {离职-随时到岗, 在职-考虑机会}  AND  resume.status = 公开
 * </pre>
 * 把规则集中在一处的原因：可见性判断散落在各处是隐私泄露的经典成因——
 * 搜索接口过滤了、详情接口忘了过滤，等于没做。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumePrivacyService {

    /** 可被搜到的求职状态。其余（如「在职-暂不考虑」）完全不出现 */
    public static final Set<String> SEARCHABLE_JOB_STATUS = Set.of("离职-随时到岗", "在职-考虑机会");

    public static final int RESUME_PUBLIC = 1;

    private final ObjectMapper objectMapper;

    /** 是否允许出现在人才库检索结果里 */
    public boolean searchable(Resume resume, JobPreference preference) {
        if (resume == null || resume.getStatus() == null || resume.getStatus() != RESUME_PUBLIC) {
            return false;
        }
        if (preference == null || preference.getJobStatus() == null) {
            return false;   // 没填求职状态 → 默认不可见（最小可见原则）
        }
        return SEARCHABLE_JOB_STATUS.contains(preference.getJobStatus());
    }

    /**
     * 匿名化显示名：「张先生 / 5 年经验 / 某互联网公司」中的第一段。
     * HR 在同意之前<b>看不到真实姓名</b>。
     */
    public String anonymousName(Resume resume) {
        String name = resume.getName();
        if (name == null || name.isBlank()) {
            return "求职者";
        }
        String surname = name.substring(0, 1);
        String gender = resume.getGender();
        if ("女".equals(gender)) {
            return surname + "女士";
        }
        if ("男".equals(gender)) {
            return surname + "先生";
        }
        return surname + "同学";
    }

    /** 屏蔽公司列表（库里存的是 JSON 数组字符串） */
    public List<Long> blockedCompanyIds(JobPreference preference) {
        if (preference == null || preference.getBlockedCompanyIds() == null
                || preference.getBlockedCompanyIds().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(preference.getBlockedCompanyIds(), new TypeReference<List<Long>>() {
            });
        } catch (Exception e) {
            // 解析失败按"没有屏蔽"处理是危险的，但按"屏蔽全部"会让用户搜不到人。
            // 折中：记录告警并返回空——写入路径是我们自己序列化的，解析失败说明数据被外部改坏了。
            log.warn("blocked_company_ids 解析失败，按未屏蔽处理: {}", preference.getBlockedCompanyIds());
            return List.of();
        }
    }

    /** 某个企业是否被该求职者屏蔽 */
    public boolean blockedBy(JobPreference preference, Long companyId) {
        return companyId != null && blockedCompanyIds(preference).contains(companyId);
    }

    /**
     * 组装索引文档。<b>这里做匿名化</b>——写进去的文档里没有真实姓名/联系方式，
     * 因此搜索、快照、日志都不可能把它们泄露出去。
     */
    public ResumeDocument toDocument(Resume resume, JobPreference preference, ResumeParseDetail detail) {
        ResumeDocument doc = new ResumeDocument();
        doc.setId(resume.getId());
        doc.setUserId(resume.getUserId());
        doc.setStatus(resume.getStatus());
        doc.setJobStatus(preference == null ? null : preference.getJobStatus());
        doc.setAnonymousName(anonymousName(resume));
        doc.setExpectCity(firstNonBlank(resume.getExpectCity(),
                preference == null ? null : preference.getExpectCity()));
        doc.setExpectSalaryMin(resume.getExpectSalaryMin() != null
                ? resume.getExpectSalaryMin() : (preference == null ? null : preference.getExpectSalaryMin()));
        doc.setExpectSalaryMax(resume.getExpectSalaryMax() != null
                ? resume.getExpectSalaryMax() : (preference == null ? null : preference.getExpectSalaryMax()));
        doc.setExpectPosition(resume.getExpectPosition());
        doc.setBlockedCompanyIds(blockedCompanyIds(preference));
        if (detail != null) {
            doc.setSkills(detail.getSkills());
            doc.setEducation(detail.getEducation());
            doc.setWorkYears(detail.getWorkYears());
        }
        doc.setUpdateTime(System.currentTimeMillis());
        return doc;
    }

    private String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }
}
