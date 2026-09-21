package com.ll.hirehub.api;

import com.ll.hirehub.api.dto.ResumeDTO;
import com.ll.hirehub.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * resume 服务内部契约
 */
@FeignClient(name = "hirehub-resume", fallbackFactory = FeignFallbacks.ResumeClientFallbackFactory.class)
public interface ResumeClient {

    @GetMapping("/internal/{id}")
    Result<ResumeDTO> getResume(@PathVariable("id") Long id);
}
