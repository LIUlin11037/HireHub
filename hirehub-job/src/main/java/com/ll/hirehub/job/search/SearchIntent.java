package com.ll.hirehub.job.search;

import lombok.Data;

/**
 * 自然语言搜索解析出的结构化意图（见 D-29）。
 * <p>
 * 字段与 ES 的过滤维度一一对应；大模型 / 规则解析只负责产出这个结构，
 * 真正的检索仍由 {@link JobSearchService} 完成。
 */
@Data
public class SearchIntent {

    private String keyword;
    private String city;
    private Integer salaryMin;
    private Integer salaryMax;
    private String education;
    private String experience;
}
