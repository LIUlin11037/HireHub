package com.ll.hirehub.company.controller;

import com.ll.hirehub.common.result.Result;
import com.ll.hirehub.company.search.CompanySearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 企业搜索（company_index，见架构文档 §6.3 / D-22）。
 * <p>
 * 只返回已认证企业；无在招职位也能被搜到（公司主页本身有价值，见 D-22 边界）。
 */
@RestController
@RequestMapping("/api/company")
@RequiredArgsConstructor
public class CompanySearchController {

    private final CompanySearchService companySearchService;

    @GetMapping("/search")
    public Result<CompanySearchService.SearchResult> search(
            @RequestParam String keyword,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String industry,
            @RequestParam(required = false, defaultValue = "10") int size) {
        return Result.ok(companySearchService.search(keyword, city, industry, size));
    }
}
