package com.ll.hirehub.resume.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 简历解析结果（见 Q-03）。
 * <p>
 * 单独一张表而不是往 resume 里加十几个列：
 * 解析是<b>机器推断</b>的结果，用户手填的字段才是权威。分开存才能在"解析错了"时
 * 只清解析结果、不伤用户数据，也方便重试覆盖。
 */
@Data
@TableName("resume_parse_detail")
public class ResumeParseDetail {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long resumeId;
    private Long userId;

    /** 来源类型：PDF / DOCX / XLSX / TXT / UNKNOWN */
    private String sourceType;

    /** 抽取出的技能关键词（JSON 数组字符串），用于 resume_index 检索 */
    private String skills;

    private String education;

    /** 工作年限（从文本里识别，识别不到为 null） */
    private Integer workYears;

    /** 工作经历片段（原文摘要） */
    private String workExperience;

    /** 抽取出的全文，供人工核对与再解析（不索引，避免把整份简历灌进 ES） */
    private String rawText;

    /** 解析失败原因（无可提取文本 / 加密文件 / 读取失败） */
    private String parseError;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
