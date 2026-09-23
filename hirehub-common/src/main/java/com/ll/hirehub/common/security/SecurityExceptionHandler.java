package com.ll.hirehub.common.security;

import com.ll.hirehub.common.result.Result;
import com.ll.hirehub.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * {@code @PreAuthorize} 拒绝时的响应转换（见 D-07）。
 * <p>
 * <b>为什么必须单独有这个 Advice</b>：方法级鉴权抛出的 {@link AccessDeniedException} 发生在
 * DispatcherServlet 内部，会先被 MVC 的异常解析器处理——它<b>到不了</b> Spring Security 的
 * ExceptionTranslationFilter。而 GlobalExceptionHandler 的兜底是 500，会把"无权限"错报成"系统繁忙"。
 * <p>
 * {@code @ConditionalOnClass} 保护：hirehub-common 被 8 个服务复用（含没有 Security 的），
 * 只有引入 spring-boot-starter-security 的服务才会装配这个 Advice。
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
@ConditionalOnClass(AccessDeniedException.class)
public class SecurityExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDenied(AccessDeniedException e) {
        log.warn("方法级鉴权拒绝：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.fail(ResultCode.FORBIDDEN));
    }
}
