package com.ll.hirehub.common.exception;

import com.ll.hirehub.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常：抛出时带错误码，由 GlobalExceptionHandler 统一转成 Result
 * 默认码用 30xxx BUSINESS_ERROR 而不是 10002 SYSTEM_ERROR——
 * 业务校验失败（如「非法状态迁移」「企业未通过认证」）不该被报成「系统繁忙」。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(String message) {
        this(ResultCode.BUSINESS_ERROR.getCode(), message);
    }

    public BusinessException(ResultCode resultCode) {
        this(resultCode.getCode(), resultCode.getMessage());
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
