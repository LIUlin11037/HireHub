package com.ll.hirehub.company.service;

/**
 * 企业工商核验接口（见 D-18）：
 * 开发用 Mock（信用代码校验位真算法 + 模拟三要素），生产切阿里云市场 API。
 */
public interface CompanyVerifier {

    CompanyVerifyResult verify(String companyName, String creditCode, String legalPersonName);

    /**
     * 核验渠道名（审计 R-08 / D-18 可插拔开关）：留档用，由实现自报——
     * Mock 实现报 "MOCK"，接阿里云等真实渠道后报各自渠道名，留档不再失真。
     */
    default String channel() {
        return "UNKNOWN";
    }
}
