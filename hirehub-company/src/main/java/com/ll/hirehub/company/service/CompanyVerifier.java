package com.ll.hirehub.company.service;

/**
 * 企业工商核验接口（见 D-18）：
 * 开发用 Mock（信用代码校验位真算法 + 模拟三要素），生产切阿里云市场 API。
 */
public interface CompanyVerifier {

    CompanyVerifyResult verify(String companyName, String creditCode, String legalPersonName);
}
