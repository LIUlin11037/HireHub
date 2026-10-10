package com.ll.hirehub.api;

import com.ll.hirehub.api.dto.IdNameDTO;
import com.ll.hirehub.api.dto.JobDTO;
import com.ll.hirehub.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * job 服务内部契约
 */
@FeignClient(name = "hirehub-job", fallbackFactory = FeignFallbacks.JobClientFallbackFactory.class)
public interface JobClient {

    @GetMapping("/internal/{id}")
    Result<JobDTO> getJob(@PathVariable("id") Long id);

    /** 批量查职位标题（投递列表冗余 jobTitle 用，见 D-41；一页只调一次，避免 N+1） */
    @GetMapping("/internal/titles")
    Result<List<IdNameDTO>> listTitles(@RequestParam("ids") List<Long> ids);
}
