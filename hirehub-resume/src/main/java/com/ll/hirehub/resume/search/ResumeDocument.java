package com.ll.hirehub.resume.search;

import lombok.Data;

import java.util.List;

/**
 * 人才库索引文档（见 D-23）。
 * <p>
 * <b>字段准入的硬规则：这里不可能出现真实姓名、手机号、邮箱</b>。
 * 匿名化在<b>写入时</b>完成——如果靠查询时脱敏，任何一处漏写就是把个人信息泄露出去，
 * 而"写进去就不存在"是唯一不依赖调用方自觉的方案。
 * <p>
 * 联系方式（手机 / 邮箱 / 真实姓名）只存 MySQL，且只有求职者对本企业同意后才由
 * {@code /hr-detail} 返回。
 */
@Data
public class ResumeDocument {

    private Long id;
    private Long userId;

    /** 简历公开开关：1 公开 / 0 保密 */
    private Integer status;

    /** 求职状态：只有「离职-随时到岗 / 在职-考虑机会」才可被搜到（见 D-23） */
    private String jobStatus;

    /** 匿名化显示名，如「张先生」 */
    private String anonymousName;

    private String expectCity;
    private Integer expectSalaryMin;
    private Integer expectSalaryMax;
    private String expectPosition;

    /** 解析出的技能（IK 分词，权重高于期望职位描述） */
    private String skills;

    private String education;
    private Integer workYears;

    /**
     * 屏蔽公司 ID 列表（keyword 数组）。
     * 搜索时用 {@code must_not terms} 过滤掉——在职求职者最怕被现公司看到（D-23）。
     */
    private List<Long> blockedCompanyIds;

    /** 最近更新时间（对账用） */
    private Long updateTime;
}
