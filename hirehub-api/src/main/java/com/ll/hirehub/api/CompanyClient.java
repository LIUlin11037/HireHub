package com.ll.hirehub.api;

import com.ll.hirehub.api.dto.CompanyMemberDTO;
import com.ll.hirehub.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * company 服务内部契约（job/delivery 等服务调用，见 D-12 跨服务数据用 Feign）
 */
@FeignClient(name = "hirehub-company", fallbackFactory = FeignFallbacks.CompanyClientFallbackFactory.class)
public interface CompanyClient {

    /** 查某人在某企业的成员关系（无则 data=null） */
    @GetMapping("/internal/member")
    Result<CompanyMemberDTO> getMember(@RequestParam("companyId") Long companyId,
                                       @RequestParam("userId") Long userId);

    /** 查某用户加入的全部企业及角色（`GET /api/auth/me/identities` 的数据源，见 D-06） */
    @GetMapping("/internal/user-companies")
    Result<List<CompanyMemberDTO>> listUserCompanies(@RequestParam("userId") Long userId);

    /** 查企业在职成员的用户 ID（状态变更通知 HR 用，见 §6.2 通知映射） */
    @GetMapping("/internal/member-ids")
    Result<List<Long>> listMemberUserIds(@RequestParam("companyId") Long companyId);

    /** 查企业认证状态（0 待审核 / 1 通过 / 2 驳回 / 3 已撤销） */
    @GetMapping("/internal/{id}/verify-status")
    Result<Integer> getVerifyStatus(@PathVariable("id") Long id);
}
