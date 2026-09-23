package com.ll.hirehub.resume.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 联系方式查看同意（见 D-23：「联系方式需同意」）。
 * <p>
 * 匿名化的边界是"同意"：HR 只能看到"张先生 / 5 年经验"。要看真实姓名与联系方式，
 * 必须由求职者本人对<b>这一家企业</b>点头——一次同意只对一家企业生效，
 * 不能用一次全局同意把简历变成公开数据。
 */
@Data
@TableName("resume_contact_consent")
public class ResumeContactConsent {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_AGREED = 1;
    public static final int STATUS_REJECTED = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long resumeId;

    /** 简历归属人（求职者），同意动作只能由他本人发起 */
    private Long seekerUserId;

    private Long companyId;

    /** 发起请求的 HR */
    private Long hrUserId;

    /** 0 待同意 / 1 已同意 / 2 已拒绝 */
    private Integer status;

    private LocalDateTime requestTime;
    private LocalDateTime consentTime;
}
