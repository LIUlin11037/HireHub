package com.ll.hirehub.api.dto;

import lombok.Data;

@Data
public class ResumeDTO {

    private Long id;
    private Long userId;
    private String title;
    private String name;
    private String gender;
    private String birth;
    private String phone;
    private String email;
    private String expectCity;
    private Integer expectSalaryMin;
    private Integer expectSalaryMax;
    private String expectPosition;
    private Integer status;
}
