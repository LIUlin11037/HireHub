package com.ll.hirehub.resume.dto;

import lombok.Data;

@Data
public class SavePreferenceRequest {

    private String jobStatus;
    private String expectCategoryIds;
    private String expectCity;
    private Integer expectSalaryMin;
    private Integer expectSalaryMax;
    private String blockedCompanyIds;
}
