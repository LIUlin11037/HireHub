package com.ll.hirehub.company.controller;

import com.ll.hirehub.common.exception.BusinessException;
import com.ll.hirehub.common.result.Result;
import com.ll.hirehub.common.result.ResultCode;
import com.ll.hirehub.company.dto.AdminVerifyRequest;
import com.ll.hirehub.company.entity.Company;
import com.ll.hirehub.company.service.CompanyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 管理端接口（见 D-07）：不拆独立 admin 服务，放在 company 服务 /admin/ 子路径。
 * 权限校验：从网关注入的 X-User-Roles 判断是否含 PLATFORM_ADMIN。
 * （后续可用 Spring Security @PreAuthorize 替代此手写校验）
 */
@RestController
@RequestMapping("/api/company/admin")
@RequiredArgsConstructor
public class CompanyAdminController {

    private final CompanyService companyService;

    @PostMapping("/{id}/verify")
    public Result<Void> verify(@RequestHeader("X-User-Roles") String roles,
                               @RequestHeader("X-User-Id") Long operatorId,
                               @PathVariable Long id,
                               @RequestBody AdminVerifyRequest req) {
        requireAdmin(roles);
        companyService.adminVerify(operatorId, id, req);
        return Result.ok();
    }

    @GetMapping("/list")
    public Result<List<Company>> listPending(@RequestHeader("X-User-Roles") String roles) {
        requireAdmin(roles);
        return Result.ok(companyService.listPending());
    }

    private void requireAdmin(String roles) {
        if (roles == null || !Arrays.asList(roles.split(",")).contains("PLATFORM_ADMIN")) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }
}
