package com.ll.hirehub.resume.controller;

import com.ll.hirehub.common.result.Result;
import com.ll.hirehub.resume.entity.ResumeContactConsent;
import com.ll.hirehub.resume.entity.ResumeParseDetail;
import com.ll.hirehub.resume.entity.ResumeViewLog;
import com.ll.hirehub.resume.search.ResumeSearchService;
import com.ll.hirehub.resume.service.ResumeHrService;
import com.ll.hirehub.resume.vo.ResumeHrDetailVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 人才库与隐私同意接口（见 D-23）。
 * <p>
 * HR 侧接口都要求 {@code X-Company-Id}：人才库是<b>按企业维度授权</b>的，
 * 不能因为"这个人是某个企业的 HR"就看到全部简历。
 */
@RestController
@RequestMapping("/api/resume")
@RequiredArgsConstructor
public class ResumeHrController {

    private final ResumeHrService resumeHrService;

    // ------------------------------------------------------------ HR 侧

    /** 人才库检索（匿名结果 + 屏蔽公司过滤） */
    @GetMapping("/search")
    public Result<ResumeSearchService.SearchResult> search(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader(value = "X-Company-Id", required = false) Long companyId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String education,
            @RequestParam(required = false) Integer salaryMin,
            @RequestParam(required = false) Integer minWorkYears,
            @RequestParam(required = false) String searchAfter,
            @RequestParam(required = false, defaultValue = "10") int size) {
        return Result.ok(resumeHrService.search(userId, companyId, keyword, city, education,
                salaryMin, minWorkYears, searchAfter, size));
    }

    /** 简历详情（匿名字段；联系方式需本人同意后才下发） */
    @GetMapping("/{id}/hr-detail")
    public Result<ResumeHrDetailVO> hrDetail(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader(value = "X-Company-Id", required = false) Long companyId,
            @PathVariable Long id) {
        return Result.ok(resumeHrService.detail(userId, companyId, id));
    }

    /** HR 发起查看联系方式申请 */
    @PostMapping("/{id}/contact-request")
    public Result<Long> requestContact(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader(value = "X-Company-Id", required = false) Long companyId,
            @PathVariable Long id) {
        return Result.ok(resumeHrService.requestContact(userId, companyId, id));
    }

    // -------------------------------------------------------- 求职者侧

    /** 待我处理的联系方式申请 */
    @GetMapping("/contact-consents")
    public Result<List<ResumeContactConsent>> pendingConsents(@RequestHeader("X-User-Id") Long userId) {
        return Result.ok(resumeHrService.pendingConsents(userId));
    }

    /** 同意 / 拒绝某企业的查看申请 */
    @PostMapping("/consent/{consentId}")
    public Result<Void> consent(@RequestHeader("X-User-Id") Long userId,
                                @PathVariable Long consentId,
                                @RequestParam(defaultValue = "true") boolean agree) {
        resumeHrService.consent(userId, consentId, agree);
        return Result.ok();
    }

    /** 谁看过我 */
    @GetMapping("/{id}/view-logs")
    public Result<List<ResumeViewLog>> viewLogs(@RequestHeader("X-User-Id") Long userId,
                                               @PathVariable Long id) {
        return Result.ok(resumeHrService.viewLogs(userId, id));
    }

    /** 简历解析结果（含失败原因） */
    @GetMapping("/{id}/parse-result")
    public Result<ResumeParseDetail> parseResult(@RequestHeader("X-User-Id") Long userId,
                                                 @PathVariable Long id) {
        return Result.ok(resumeHrService.parseResult(userId, id));
    }

    /** 手动重建索引（改了隐私设置后想让变更立即生效时用） */
    @PostMapping("/{id}/refresh-index")
    public Result<Void> refreshIndex(@RequestHeader("X-User-Id") Long userId,
                                     @PathVariable Long id) {
        resumeHrService.refreshIndex(userId, id);
        return Result.ok();
    }
}
