package com.ll.hirehub.company.service;

import com.ll.hirehub.company.util.CreditCodeUtil;
import org.springframework.stereotype.Component;

/**
 * 企业核验 Mock 实现：
 *   · 统一社会信用代码校验位 → 真算法（GB 32100，能识别编造的号）
 *   · 三要素一致性（名称/法人/信用代码）→ 模拟通过
 *   · 经营状态 → 模拟返回「存续」
 * 生产实现（AliyunCompanyVerifier）走阿里云市场企业三要素校验，配置开关切换。
 */
@Component
public class MockCompanyVerifier implements CompanyVerifier {

    @Override
    public CompanyVerifyResult verify(String companyName, String creditCode, String legalPersonName) {
        if (!CreditCodeUtil.isValid(creditCode)) {
            return CompanyVerifyResult.builder()
                    .valid(false)
                    .message("统一社会信用代码校验位错误")
                    .build();
        }
        // 三要素一致性 + 经营状态：mock 通过
        return CompanyVerifyResult.builder()
                .valid(true)
                .businessStatus("存续")
                .build();
    }
}
