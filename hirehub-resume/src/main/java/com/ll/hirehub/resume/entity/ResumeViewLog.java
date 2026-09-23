package com.ll.hirehub.resume.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 简历查看留痕（见 D-23）。
 * <p>
 * 为什么"留痕"是隐私模型的一部分而不是可有可无的日志：默认最小可见只约束了"能看到什么"，
 * 留痕回答的是"<b>谁看过我</b>"——求职者对平台有知情权，这也是《个人信息保护法》下
 * 处理个人信息的常规要求。
 */
@Data
@TableName("resume_view_log")
public class ResumeViewLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long resumeId;

    /** 查看人（HR 用户 ID） */
    private Long viewerUserId;

    /** 查看人所属企业——屏蔽公司后仍能追溯"这家公司看过我" */
    private Long companyId;

    /** 场景：SEARCH_RESULT / DETAIL / CONTACT */
    private String scene;

    private LocalDateTime createTime;
}
