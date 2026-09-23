package com.ll.hirehub.company.dto;

import lombok.Data;

/**
 * 提交认证的结果（见 D-24）：把风险等级与后续动作回给前端。
 */
@Data
public class SubmitVerifyResult {

    /** LOW = 低风险自动通过；MEDIUM = 需法人授权；REJECTED = 基础核验不通过 */
    private String riskLevel;
    /** 低风险是否已自动通过 */
    private boolean autoApproved;
    /** 中风险时生成的法人授权令牌（演示环境直接返回，生产走邮件/短信） */
    private String authorizationToken;
}
