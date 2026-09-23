package com.ll.hirehub.job.controller;

import com.ll.hirehub.api.AuthClient;
import com.ll.hirehub.api.dto.OperationLogRequest;
import com.ll.hirehub.common.result.Result;
import com.ll.hirehub.job.service.JobAdminService;
import com.ll.hirehub.job.vo.JobStatisticsVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端职位接口（见 D-07）：下架违规职位 + 职位维度统计。
 * <p>
 * 放在 job 服务而不是独立的 admin 服务——下架职位属于 job 领域，拆服务反而破坏边界（D-07）。
 */
@Slf4j
@RestController
@RequestMapping("/api/job/admin")
@RequiredArgsConstructor
public class JobAdminController {

    private final JobAdminService jobAdminService;
    private final AuthClient authClient;

    /** 下架违规职位（管理员） */
    @PutMapping("/{id}/offline")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<Void> offline(@RequestHeader("X-User-Id") Long operatorId,
                               @PathVariable Long id,
                               @RequestParam(required = false) String reason,
                               HttpServletRequest httpRequest) {
        jobAdminService.offline(id, reason);
        audit(operatorId, "JOB_OFFLINE", id, "reason=" + reason, httpRequest);
        return Result.ok();
    }

    /** 职位维度统计（管理员） */
    @GetMapping("/statistics")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<JobStatisticsVO> statistics() {
        return Result.ok(jobAdminService.statistics());
    }

    /** 审计旁路：失败只记日志，不影响下架本身 */
    private void audit(Long operatorId, String action, Long jobId, String detail,
                       HttpServletRequest httpRequest) {
        try {
            OperationLogRequest log = OperationLogRequest.of(
                    operatorId, "JOB", action, "JOB", jobId, detail);
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
