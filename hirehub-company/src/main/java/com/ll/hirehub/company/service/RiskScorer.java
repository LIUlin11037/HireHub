package com.ll.hirehub.company.service;

import org.springframework.stereotype.Component;

/**
 * 风险评分（见 §6.7 / D-24 第二层：企业归属）。
 * <p>
 * 刻意「简单加权，不引规则引擎框架」——规则引擎对这个规模是过度设计。
 * 先算风险等级，再决定走「法人授权」还是「人工审核」：
 * <pre>
 *   低风险 → 材料齐全 + 实名 → 自动通过
 *   中风险 → 法人授权令牌闭环
 * </pre>
 * 工商经营状态不在这里判（非「存续」在基础核验阶段直接驳回）。
 * <p>
 * Mock 边界：邮箱域名、企业成立年限两个维度当前没有数据源，暂不参与打分（见 D-24 表格）。
 */
@Component
public class RiskScorer {

    public static final String LOW = "LOW";
    public static final String MEDIUM = "MEDIUM";

    /**
     * @param realNameVerified 申请人是否已实名（第一层）
     * @param hasLicense       是否上传了营业执照/授权书
     * @param everRejected     同企业是否曾被驳回
     */
    public String score(boolean realNameVerified, boolean hasLicense, boolean everRejected) {
        int score = 0;
        if (realNameVerified) {
            score += 1;
        }
        if (hasLicense) {
            score += 1;
        }
        if (everRejected) {
            score -= 2;
        }
        return score >= 2 ? LOW : MEDIUM;
    }
}
