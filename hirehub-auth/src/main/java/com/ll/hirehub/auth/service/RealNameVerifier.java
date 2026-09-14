package com.ll.hirehub.auth.service;

/**
 * 实名核验接口：抽象出来，生产环境替换实现类即可接入三方实名核身（见 D-16 / §6.7）
 */
public interface RealNameVerifier {

    /**
     * 核验姓名 + 身份证号，失败抛 BusinessException
     */
    void verify(String realName, String idCard);
}
