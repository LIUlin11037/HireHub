package com.ll.hirehub.auth.service;

import com.ll.hirehub.auth.util.IdCardUtil;
import com.ll.hirehub.common.exception.BusinessException;
import com.ll.hirehub.common.result.ResultCode;
import org.springframework.stereotype.Component;

/**
 * 实名核验 Mock 实现：
 *   · 身份证校验位 → 真算法（GB 11643，能识别编造的号）
 *   · 姓名与身份证是否一致 → 模拟通过（真实场景需公安/三方核身接口）
 */
@Component
public class MockRealNameVerifier implements RealNameVerifier {

    @Override
    public void verify(String realName, String idCard) {
        if (!IdCardUtil.isValid(idCard)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "身份证号校验位错误");
        }
        // 人证一致性：mock 直接通过
    }
}
