package com.ll.hirehub.job.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("job")
public class Job {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long companyId;
    private Long publisherId;
    private String title;
    private Long categoryId;
    private String skills;
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

    private Integer status;
    private String offlineReason;
    private LocalDateTime publishTime;
    private LocalDateTime expireTime;
    private Integer deliveryCount;
    private Integer viewCount;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
