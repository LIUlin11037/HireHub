package com.ll.hirehub.resume.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("job_preference")
public class JobPreference {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String jobStatus;
    private String expectCategoryIds;
    private String expectCity;
    private Integer expectSalaryMin;
    private Integer expectSalaryMax;
    private String blockedCompanyIds;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
