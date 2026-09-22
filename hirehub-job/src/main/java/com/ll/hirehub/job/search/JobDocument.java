package com.ll.hirehub.job.search;

import lombok.Data;

/**
 * 职位 ES 文档（job_index，见架构文档 §6.3 / D-22）。
 * <p>
 * 只冗余搜索/展示需要的字段，不塞大文本；source 即文档本身。
 */
@Data
public class JobDocument {

    private Long id;
    private Long companyId;
    private String title;
    private String skills;      // JSON 数组字符串，如 ["Java","Spring Boot"]
    private String city;
    private String district;
    private Integer salaryMin;
    private Integer salaryMax;
    private String education;
    private String experience;
    private Long categoryId;
    private String description;
    private Integer status;     // 1 = 招聘中（只索引上线职位）
    private Long publishTime;   // epoch millis（草稿未上线时为 createTime）
}
