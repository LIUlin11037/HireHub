package com.ll.hirehub.api;

import com.ll.hirehub.api.dto.JobDTO;
import com.ll.hirehub.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * job 服务内部契约
 */
@FeignClient(name = "hirehub-job")
public interface JobClient {

    @GetMapping("/internal/{id}")
    Result<JobDTO> getJob(@PathVariable("id") Long id);
}
