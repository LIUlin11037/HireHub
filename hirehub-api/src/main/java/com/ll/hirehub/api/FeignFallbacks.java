package com.ll.hirehub.api;

import com.ll.hirehub.api.dto.CompanyMemberDTO;
import com.ll.hirehub.api.dto.DeliveryDTO;
import com.ll.hirehub.api.dto.IdNameDTO;
import com.ll.hirehub.api.dto.JobDTO;
import com.ll.hirehub.api.dto.OperationLogRequest;
import com.ll.hirehub.api.dto.ResumeDTO;
import com.ll.hirehub.common.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;

import java.util.Collections;
import java.util.List;

/**
 * Feign 降级工厂集合（见架构文档 §8.3）。
 * <p>
 * 用 fallbackFactory 而不是 fallback：能拿到具体异常，便于日志与排查。
 * <p>
 * 降级策略统一「失败关闭」：返回 {@code data=null} / 空集合。
 * 上层对 null 的既有判断天然安全——成员查不到 → 403、认证状态查不到 → 未认证、职位查不到 → 不可投递。
 * 这样下游挂了，链路不会雪崩成 500，而是退化成明确的业务拒绝。
 */
public final class FeignFallbacks {

    /** 降级日志（审计 R-05：fallback 不再静默，所有降级路径可用它留痕） */
    private static final Logger log = LoggerFactory.getLogger(FeignFallbacks.class);

    private FeignFallbacks() {
    }

    public static class CompanyClientFallbackFactory implements FallbackFactory<CompanyClient> {
        @Override
        public CompanyClient create(Throwable cause) {
            return new CompanyClient() {
                @Override
                public Result<CompanyMemberDTO> getMember(Long companyId, Long userId) {
                    return Result.ok(null);
                }

                @Override
                public Result<List<CompanyMemberDTO>> listUserCompanies(Long userId) {
                    return Result.ok(Collections.emptyList());
                }

                @Override
                public Result<List<Long>> listMemberUserIds(Long companyId) {
                    return Result.ok(Collections.emptyList());
                }

                @Override
                public Result<Integer> getVerifyStatus(Long id) {
                    return Result.ok(null);
                }

                @Override
                public Result<List<IdNameDTO>> listNames(List<Long> ids) {
                    // 降级：空集合。投递列表照常渲染，只是企业名显示为空。
                    return Result.ok(Collections.emptyList());
                }
            };
        }
    }

    public static class AuthClientFallbackFactory implements FallbackFactory<AuthClient> {
        @Override
        public AuthClient create(Throwable cause) {
            return new AuthClient() {
                @Override
                public Result<Integer> getRealNameStatus(Long userId) {
                    return Result.ok(null);
                }

                @Override
                public Result<Void> saveOperationLog(OperationLogRequest req) {
                    // 审计 R-05：降级不能静默——管理操作审计（D-07 误操作可追溯）丢了必须留痕，
                    // 否则"审计丢了就丢了"形成静默缺口。记 error 日志带上原因与操作内容待补偿。
                    log.error("[审计降级] saveOperationLog 失败，操作审计未入库: action={} operator={} target={}/{}, err={}",
                            req.getAction(), req.getOperatorId(), req.getTargetType(), req.getTargetId(),
                            cause == null ? "unknown" : cause.getMessage(), cause);
                    return Result.ok();
                }
            };
        }
    }

    public static class JobClientFallbackFactory implements FallbackFactory<JobClient> {
        @Override
        public JobClient create(Throwable cause) {
            // ⚠️ JobClient 已不止一个方法，这里不能再写成 lambda
            return new JobClient() {
                @Override
                public Result<JobDTO> getJob(Long id) {
                    return Result.ok(null);
                }

                @Override
                public Result<List<IdNameDTO>> listTitles(List<Long> ids) {
                    return Result.ok(Collections.emptyList());
                }
            };
        }
    }

    public static class ResumeClientFallbackFactory implements FallbackFactory<ResumeClient> {
        @Override
        public ResumeClient create(Throwable cause) {
            // ⚠️ 同上：ResumeClient 也已是多方法接口
            return new ResumeClient() {
                @Override
                public Result<ResumeDTO> getResume(Long id) {
                    return Result.ok(null);
                }

                @Override
                public Result<List<IdNameDTO>> listNames(List<Long> ids) {
                    return Result.ok(Collections.emptyList());
                }
            };
        }
    }

    public static class DeliveryClientFallbackFactory implements FallbackFactory<DeliveryClient> {
        @Override
        public DeliveryClient create(Throwable cause) {
            return id -> Result.ok(null);
        }
    }
}
