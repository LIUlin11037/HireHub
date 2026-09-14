package com.ll.hirehub.job.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateJobRequest {

    @NotNull(message = "企业不能为空")
    private Long companyId;

    @NotBlank(message = "职位标题不能为空")
    private String title;

    private Long categoryId;
    private String skills;          // JSON 数组字符串，如 ["Java","Spring Boot"]
    private String city;
    private String district;
    private String address;
    private Integer remote;
    private Integer salaryMin;
    private Integer salaryMax;
    private String salaryType;
    private Integer negotiable;
    private String education;
    private String experience;
    private Integer headcount;
    private String jobType;
    private String description;
    private String requirement;
}
