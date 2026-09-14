package com.ll.hirehub.resume.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("resume")
public class Resume {

    @TableId(type = IdType.AUTO)
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
    private Integer parseStatus;
    private Long attachmentId;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
