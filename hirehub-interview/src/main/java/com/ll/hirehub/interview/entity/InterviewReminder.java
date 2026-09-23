package com.ll.hirehub.interview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 面试提醒排布记录（见 D-32）。
 * <p>
 * 这张表是提醒的「唯一真相」：延迟消息只带 (interviewId, tier, token)，
 * 消费时必须回查本表——状态与令牌决定这条消息还算不算数。
 * <p>
 * 为什么不能只靠延迟消息本身：
 * <ol>
 *   <li><b>改期</b>：旧延迟消息到期时面试时间已变，靠 {@code token} 识别并丢弃。</li>
 *   <li><b>取消 / 已完成</b>：消息拦不住，靠 {@code status} 作废。</li>
 *   <li><b>队头阻塞与丢消息</b>：TTL 方案有队头阻塞，靠兜底扫描按 {@code remind_time} 补发。</li>
 * </ol>
 * {@code uk(interview_id, tier)} 让"同一场面试的同一档提醒"天然只有一条，重复排布变成更新。
 */
@Data
@TableName("interview_reminder")
public class InterviewReminder {

    /** 待发 */
    public static final int STATUS_PENDING = 0;
    /** 已发 */
    public static final int STATUS_SENT = 1;
    /** 已作废（面试取消 / 改期被取代 / 面试已开始） */
    public static final int STATUS_CANCELLED = 2;

    public static final String TIER_1D = "1D";
    public static final String TIER_30M = "30M";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long interviewId;

    /** 提前量档位：1D / 30M */
    private String tier;

    /** 本批次令牌：改期后重生，用于丢弃被取代的旧延迟消息 */
    private String token;

    /** 应当提醒的时间点 = 面试时间 − 提前量 */
    private LocalDateTime remindTime;

    /** 0 待发 / 1 已发 / 2 已作废 */
    private Integer status;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
