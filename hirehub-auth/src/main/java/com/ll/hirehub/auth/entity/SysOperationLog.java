package com.ll.hirehub.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端操作审计（见 D-07）。
 * <p>
 * 为什么不给每个服务建一张自己的日志表：审计的价值在于"一个管理员干了什么"要能一次查全。
 * 分散存储就得跨库聚合（违反 D-13），所以集中放 auth，业务服务通过内部接口上报。
 */
@Data
@TableName("sys_operation_log")
public class SysOperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long operatorId;
    private String operatorName;

    /** 模块：COMPANY / JOB / AUTH */
    private String module;

    /** 动作：VERIFY_APPROVE / JOB_OFFLINE ... */
    private String action;

    private String targetType;
    private Long targetId;

    private String detail;
    private String ip;

    /** 与链路追踪打通：用 traceId 可反查整条调用链 */
    private String traceId;

    private LocalDateTime createTime;
}
