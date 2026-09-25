package com.ll.hirehub.common.exception;

import com.ll.hirehub.common.result.Result;
import com.ll.hirehub.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：业务异常按错误码映射 HTTP 状态，参数类异常 400，兜底 500
 * <p>
 * 只依赖 spring-web（非 spring-webmvc / 非 jakarta.servlet），
 * 因为 hirehub-common 同时被 WebFlux 网关和 MVC 服务复用。
 * Spring 自带的客户端异常统一通过 {@link ErrorResponse} 取状态码，
 * 这样就不必在 common 里引入 servlet API。
 * <p>
 * <b>但"被复用"不等于"该在网关里生效"</b>：网关组件扫描 {@code com.ll.hirehub}，会把本类
 * 一起装配进 WebFlux 上下文，于是网关内部任何异常（如 Sentinel 的 BlockException）都会被
 * 改写成 {@code code=10002} 的 HTTP 500，「请求过于频繁」被伪装成「系统繁忙」，
 * 排障时完全看不出真相（踩坑记录 #20）。所以限定只在 <b>Servlet</b> 应用里注册。
 */
@Slf4j
@RestControllerAdvice
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GlobalExceptionHandler {

    /** 业务异常：HTTP 状态由错误码决定，前端既可看 status 也可看 body.code */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException e) {
        return ResponseEntity.status(httpStatusOf(e.getCode()))
                .body(Result.fail(e.getCode(), e.getMessage()));
    }

    /** @Valid 校验失败 → 400，返回第一条字段错误信息（不要兜底成「系统繁忙」） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getDefaultMessage())
                .orElse(ResultCode.PARAM_ERROR.getMessage());
        return badRequest(message);
    }

    /** 兜底：Spring 抛出的 4xx 客户端错误（缺请求头 / 参数类型不符 / body 不可解析）原样透出状态码 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e) {
        if (e instanceof ErrorResponse errorResponse && errorResponse.getStatusCode().is4xxClientError()) {
            log.warn("客户端请求错误：{}", e.getMessage());
            return ResponseEntity.status(errorResponse.getStatusCode())
                    .body(Result.fail(ResultCode.PARAM_ERROR.getCode(), e.getMessage()));
        }
        log.error("未捕获异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ResultCode.SYSTEM_ERROR));
    }

    private ResponseEntity<Result<Void>> badRequest(String message) {
        return ResponseEntity.badRequest()
                .body(Result.fail(ResultCode.PARAM_ERROR.getCode(), message));
    }

    private HttpStatus httpStatusOf(int code) {
        return switch (code) {
            case 10001, 30001 -> HttpStatus.BAD_REQUEST;   // 参数错误 / 业务校验失败
            case 10002 -> HttpStatus.INTERNAL_SERVER_ERROR;
            case 10004 -> HttpStatus.NOT_FOUND;
            case 20001, 20002 -> HttpStatus.UNAUTHORIZED;
            case 20003 -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
