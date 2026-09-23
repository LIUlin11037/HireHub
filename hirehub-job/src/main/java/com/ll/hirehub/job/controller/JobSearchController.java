package com.ll.hirehub.job.controller;

import com.ll.hirehub.common.result.Result;
import com.ll.hirehub.job.search.JobSearchService;
import com.ll.hirehub.job.search.SearchIntent;
import com.ll.hirehub.job.search.SearchQueryParser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职位搜索（job_index，见架构文档 §6.3 / D-22 / D-29）。
 * <p>
 * {@code /search} 结构化查询；{@code /search/nl} 自然语言查询
 * （自然语言 → SearchQueryParser 解析成结构化意图 → 复用同一套 ES 查询）。
 */
@RestController
@RequestMapping("/api/job")
@RequiredArgsConstructor
public class JobSearchController {

    private final JobSearchService jobSearchService;
    private final SearchQueryParser searchQueryParser;

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

    /** 自然语言搜索：q=杭州 30k以上 本科 Java后端 */
    @GetMapping("/search/nl")
    public Result<JobSearchService.SearchResult> searchNl(
            @RequestParam String q,
            @RequestParam(required = false, defaultValue = "10") int size,
            @RequestParam(required = false) String searchAfter) {
        SearchIntent intent = searchQueryParser.parse(q);
        return Result.ok(jobSearchService.search(intent.getKeyword(), intent.getCity(),
                intent.getSalaryMin(), intent.getSalaryMax(), intent.getEducation(),
                intent.getExperience(), searchAfter, size));
    }
}
