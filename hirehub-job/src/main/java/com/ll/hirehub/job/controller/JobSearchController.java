package com.ll.hirehub.job.controller;

import com.ll.hirehub.common.result.Result;
import com.ll.hirehub.job.search.JobSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职位搜索（job_index，见架构文档 §6.3 / D-22）。
 * <p>
 * 只搜「招聘中」职位；关键词走相关性，城市/薪资/学历/经验走过滤；
 * 深分页用 searchAfter（响应里的 cursor 原样带回）。
 */
@RestController
@RequestMapping("/api/job")
@RequiredArgsConstructor
public class JobSearchController {

    private final JobSearchService jobSearchService;

    @GetMapping("/search")
    public Result<JobSearchService.SearchResult> search(
            @RequestParam String keyword,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) Integer salaryMin,
            @RequestParam(required = false) Integer salaryMax,
            @RequestParam(required = false) String education,
            @RequestParam(required = false) String experience,
            @RequestParam(required = false, defaultValue = "10") int size,
            @RequestParam(required = false) String searchAfter) {
        return Result.ok(jobSearchService.search(keyword, city, salaryMin, salaryMax,
                education, experience, searchAfter, size));
    }
}
