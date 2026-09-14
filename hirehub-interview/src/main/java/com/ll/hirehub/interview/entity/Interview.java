package com.ll.hirehub.interview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("interview")
public class Interview {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long deliveryId;
    private Long jobId;
    private Long companyId;
    private Long seekerId;
    private Integer round;
    private LocalDateTime interviewTime;
    private String interviewType;
    private String addressOrLink;
    private String interviewerName;
    private String status;
    private String cancelledBy;
    private String cancelReason;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
