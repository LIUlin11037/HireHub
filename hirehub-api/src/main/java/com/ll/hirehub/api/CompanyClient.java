package com.ll.hirehub.api;

import com.ll.hirehub.api.dto.CompanyMemberDTO;
import com.ll.hirehub.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * company 服务内部契约（job/delivery 等服务调用，见 D-12 跨服务数据用 Feign）
 */
@FeignClient(name = "hirehub-company")
public interface CompanyClient {

    /** 查某人在某企业的成员关系（无则 data=null） */
    @GetMapping("/internal/member")
    Result<CompanyMemberDTO> getMember(@RequestParam("companyId") Long companyId,
                                       @RequestParam("userId") Long userId);

    /** 查企业认证状态（0 待审核 / 1 通过 / 2 驳回 / 3 已撤销） */
    @GetMapping("/internal/{id}/verify-status")
    Result<Integer> getVerifyStatus(@PathVariable("id") Long id);
}
