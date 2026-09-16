package com.ll.hirehub.common.result;

import lombok.Getter;

/**
 * 错误码分段（见架构文档 §8.1）：
 *   0      成功
 *   10xxx  通用
 *   20xxx  认证
 *   30xxx  业务（由各服务自行扩展）
 */
@Getter
public enum ResultCode {

    SUCCESS(0, "成功"),

    // 通用 10xxx
    PARAM_ERROR(10001, "参数错误"),
    SYSTEM_ERROR(10002, "系统繁忙，请稍后重试"),
    NOT_FOUND(10004, "资源不存在"),

    // 认证 20xxx
    UNAUTHORIZED(20001, "未登录或登录已过期"),
    TOKEN_INVALID(20002, "凭证无效"),
    FORBIDDEN(20003, "无权限"),

    // 业务 30xxx
    BUSINESS_ERROR(30001, "业务校验未通过");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
