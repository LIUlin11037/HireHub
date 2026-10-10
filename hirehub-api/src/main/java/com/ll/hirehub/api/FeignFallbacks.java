package com.ll.hirehub.api;

import com.ll.hirehub.api.dto.CompanyMemberDTO;
import com.ll.hirehub.api.dto.DeliveryDTO;
import com.ll.hirehub.api.dto.IdNameDTO;
import com.ll.hirehub.api.dto.JobDTO;
import com.ll.hirehub.api.dto.OperationLogRequest;
import com.ll.hirehub.api.dto.ResumeDTO;
import com.ll.hirehub.common.result.Result;
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
                    // 审计上报降级：静默失败，绝不能因为审计中心挂了而阻断管理操作
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
