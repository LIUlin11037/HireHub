package com.ll.hirehub.delivery.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("delivery")
public class Delivery {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long jobId;
    private Long resumeId;
    private Long seekerId;
    private Long companyId;
    private String status;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
