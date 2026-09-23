package com.ll.hirehub.resume.vo;

import lombok.Data;

import java.util.List;

/**
 * HR 视角的简历详情（见 D-23）。
 * <p>
 * <b>姓名与联系方式默认不填</b>——不是填了空串再让前端隐藏，而是根本不下发。
 * 只有求职者对本企业同意后才有值，这样即使前端有 bug 也不会泄露。
 */
@Data
public class ResumeHrDetailVO {

    private Long resumeId;

    /** 匿名化显示名，如「张先生」 */
    private String anonymousName;

    private String expectCity;
    private Integer expectSalaryMin;
    private Integer expectSalaryMax;
    private String expectPosition;
    private String jobStatus;

    private List<String> skills;
    private String education;
    private Integer workYears;
    private String workExperience;

    /** 解析状态：0 待解析 / 1 解析中 / 2 成功 / 3 失败 */
    private Integer parseStatus;

    /** 联系方式是否已对本企业放开 */
    private boolean contactRevealed;

    /** 同意状态：null 未发起 / 0 待同意 / 1 已同意 / 2 已拒绝 */
    private Integer consentStatus;

    // ---------- 以下字段仅在 contactRevealed=true 时有值 ----------
    private String name;
    private String phone;
    private String email;
}
