package com.ll.hirehub.common.exception;

import com.ll.hirehub.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常：抛出时带错误码，由 GlobalExceptionHandler 统一转成 Result
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(String message) {
        this(ResultCode.SYSTEM_ERROR.getCode(), message);
    }

    public BusinessException(ResultCode resultCode) {
        this(resultCode.getCode(), resultCode.getMessage());
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
