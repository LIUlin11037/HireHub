package com.ll.hirehub.api.dto;

import lombok.Data;

/**
 * 管理端操作审计上报（见 D-07 / §6.9）。
 * <p>
 * 审计中心放在 auth（`GET /api/auth/admin/operation-log`），业务服务在管理操作成功后
 * 通过 Feign 上报一条记录。上报失败<b>不允许</b>阻断业务操作——审计是旁路，不是主流程。
 */
@Data
public class OperationLogRequest {

    private Long operatorId;

    /** 操作人账号名（避免查审计时还要回 auth 关联 sys_user） */
    private String operatorName;

    /** 模块：COMPANY / JOB / AUTH */
    private String module;

    /** 动作：VERIFY_APPROVE / VERIFY_REJECT / JOB_OFFLINE ... */
    private String action;

    /** 目标类型：COMPANY / JOB / USER */
    private String targetType;

    private Long targetId;

    /** 变更详情（人可读文本或 JSON），用于回答"他到底改了什么" */
    private String detail;

    private String ip;

    /** 链路 ID：与 traceId 打通后，"哪次请求干了这件事"一条 SQL 就能定位 */
    private String traceId;

    /**
     * 构造器：调用方只需要关心"谁、在哪个模块、做了什么、对谁做的"。
     * operatorName / ip / traceId 由审计中心与调用方各自补齐。
     */
    public static OperationLogRequest of(Long operatorId, String module, String action,
                                         String targetType, Long targetId, String detail) {
        OperationLogRequest req = new OperationLogRequest();
        req.setOperatorId(operatorId);
        req.setModule(module);
        req.setAction(action);
        req.setTargetType(targetType);
        req.setTargetId(targetId);
        req.setDetail(detail);
        return req;
    }
}
