package com.ll.hirehub.interview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("interview_feedback")
public class InterviewFeedback {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long interviewId;
    private Long recorderId;
    private String result;
    private String comment;
    private LocalDateTime createTime;
}
