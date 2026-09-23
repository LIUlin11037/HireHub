package com.ll.hirehub.auth.controller;

import com.ll.hirehub.api.dto.OperationLogRequest;
import com.ll.hirehub.auth.entity.SysOperationLog;
import com.ll.hirehub.auth.service.OperationLogService;
import com.ll.hirehub.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端接口（见 D-07）：管理端不拆服务，放所属服务的 {@code /admin/} 子路径。
 * <p>
 * 权限统一由 {@code @PreAuthorize("hasRole('PLATFORM_ADMIN')")} 声明，
 * 身份来自网关注入的 {@code X-User-Roles}（见 {@code HeaderAuthenticationFilter}）。
 */
@RestController
@RequestMapping("/api/auth/admin")
@RequiredArgsConstructor
public class AuthAdminController {

    private final OperationLogService operationLogService;

    /** 审计日志查询：答"管理员误操作怎么追溯" */
    @GetMapping("/operation-log")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<List<SysOperationLog>> operationLog(@RequestParam(required = false) Long operatorId,
                                                     @RequestParam(required = false) String module,
                                                     @RequestParam(required = false) Integer limit) {
        return Result.ok(operationLogService.query(operatorId, module, limit));
    }
}
