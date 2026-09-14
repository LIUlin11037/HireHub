package com.ll.hirehub.common.exception;

import com.ll.hirehub.common.result.Result;
import com.ll.hirehub.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：业务异常 → 兜底
 * 依赖 spring-web（非 spring-webmvc），MVC 与 WebFlux 服务均可复用
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("未捕获异常", e);
        return Result.fail(ResultCode.SYSTEM_ERROR);
    }
}
