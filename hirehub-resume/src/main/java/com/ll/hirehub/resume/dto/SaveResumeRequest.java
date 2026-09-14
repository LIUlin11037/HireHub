package com.ll.hirehub.resume.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SaveResumeRequest {

    @NotBlank(message = "姓名不能为空")
    private String name;

    private String title;
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
