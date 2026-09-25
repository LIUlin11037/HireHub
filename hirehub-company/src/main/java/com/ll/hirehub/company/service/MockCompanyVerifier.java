package com.ll.hirehub.company.service;

import com.ll.hirehub.company.util.CreditCodeUtil;
import org.springframework.stereotype.Component;

/**
 * 企业核验 Mock 实现：
 *   · 统一社会信用代码校验位 → 真算法（GB 32100，能识别编造的号）
 *   · 三要素一致性（名称/法人/信用代码）→ 模拟通过
 *   · 经营状态 → 默认「存续」
 * 生产实现（AliyunCompanyVerifier）走阿里云市场企业三要素校验，配置开关切换。
 * <p>
 * <b>为什么 Mock 也要能返回「注销」</b>：见 Q-08 的企业认证定期复核 ——
 * 复核的价值全在"发现企业已注销"这条分支上，而 Mock 永远返回「存续」的话，
 * 这条分支就没法端到端验证，只能靠读代码"。所以约定：
 * 企业名以 {@code -REVOKED} 结尾（或包含「已注销」）时，Mock 返回经营状态「注销」。
 * 这是<b>测试夹具约定</b>，不是业务规则——生产实现拿的是工商接口的真实状态。
 */
@Component
public class MockCompanyVerifier implements CompanyVerifier {

    /** 测试用标记：企业名以它结尾即视为「已注销」 */
    public static final String REVOKED_SUFFIX = "-REVOKED";
    /** 测试用标记（中文写法），方便人工演示 */
    public static final String REVOKED_KEYWORD = "已注销";
    /** 工商经营状态：注销 */
    public static final String STATUS_REVOKED = "注销";

    @Override
    public CompanyVerifyResult verify(String companyName, String creditCode, String legalPersonName) {
        if (!CreditCodeUtil.isValid(creditCode)) {
            return CompanyVerifyResult.builder()
                    .valid(false)
                    .message("统一社会信用代码校验位错误")
                    .build();
        }
        if (companyName != null
                && (companyName.endsWith(REVOKED_SUFFIX) || companyName.contains(REVOKED_KEYWORD))) {
            return CompanyVerifyResult.builder()
                    .valid(false)
                    .businessStatus(STATUS_REVOKED)
                    .message("企业已注销")
                    .build();
        }
        // 三要素一致性 + 经营状态：mock 通过
        return CompanyVerifyResult.builder()
                .valid(true)
                .businessStatus("存续")
                .build();
    }
}
