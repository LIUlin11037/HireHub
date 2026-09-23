package com.ll.hirehub.company.controller;

import com.ll.hirehub.api.AuthClient;
import com.ll.hirehub.api.dto.OperationLogRequest;
import com.ll.hirehub.common.result.Result;
import com.ll.hirehub.company.dto.AdminVerifyRequest;
import com.ll.hirehub.company.entity.Company;
import com.ll.hirehub.company.service.CompanyService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端接口（见 D-07）：不拆独立 admin 服务，放在 company 服务 {@code /admin/} 子路径。
 * <p>
 * 权限由 {@code @PreAuthorize} 统一声明，替代三期之前的手写 {@code requireAdmin(roles)}：
 * 手写校验的问题是"每个新接口都要记得写一次"，漏一个就是垂直越权；
 * 注解是声明式的，审计时扫一遍注解就能确认哪些接口受管理员保护。
 */
@Slf4j
@RestController
@RequestMapping("/api/company/admin")
@RequiredArgsConstructor
public class CompanyAdminController {

    private final CompanyService companyService;
    private final AuthClient authClient;

    @PostMapping("/{id}/verify")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<Void> verify(@RequestHeader("X-User-Id") Long operatorId,
                               @PathVariable Long id,
                               @RequestBody AdminVerifyRequest req,
                               HttpServletRequest httpRequest) {
        companyService.adminVerify(operatorId, id, req);
        audit(operatorId, Boolean.TRUE.equals(req.getApprove()) ? "VERIFY_APPROVE" : "VERIFY_REJECT",
                id, "approve=" + req.getApprove() + ", remark=" + req.getRemark(), httpRequest);
        return Result.ok();
    }

    @GetMapping("/list")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<List<Company>> listPending() {
        return Result.ok(companyService.listPending());
    }

    /**
     * 上报审计。审计是旁路能力：失败只记日志，绝不因为审计中心不可用就让审核失败。
     * （Feign 也有 fallbackFactory 兜底，这里是最后一道保险。）
     */
    private void audit(Long operatorId, String action, Long companyId, String detail,
                       HttpServletRequest httpRequest) {
        try {
            OperationLogRequest log = OperationLogRequest.of(
                    operatorId, "COMPANY", action, "COMPANY", companyId, detail);
            log.setIp(clientIp(httpRequest));
            authClient.saveOperationLog(log);
        } catch (Exception e) {
            log.warn("审计上报失败（不影响业务）: {}", e.getMessage());
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
