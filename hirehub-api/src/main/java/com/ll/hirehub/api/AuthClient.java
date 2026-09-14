package com.ll.hirehub.api;

import com.ll.hirehub.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * auth 服务内部契约
 */
@FeignClient(name = "hirehub-auth")
public interface AuthClient {

    /** 查用户实名状态（0 未认证 / 1 已认证 / 2 驳回） */
    @GetMapping("/internal/real-name-status")
    Result<Integer> getRealNameStatus(@RequestParam("userId") Long userId);
}
