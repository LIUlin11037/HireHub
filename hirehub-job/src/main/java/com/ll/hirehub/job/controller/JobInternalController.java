package com.ll.hirehub.job.controller;

import com.ll.hirehub.api.dto.JobDTO;
import com.ll.hirehub.common.result.Result;
import com.ll.hirehub.job.entity.Job;
import com.ll.hirehub.job.mapper.JobMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部接口（供 delivery 服务取职位的 companyId，见 D-12）
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class JobInternalController {

    private final JobMapper jobMapper;

    @GetMapping("/{id}")
    public Result<JobDTO> get(@PathVariable Long id) {
        Job j = jobMapper.selectById(id);
        if (j == null) {
            return Result.ok(null);
        }
        JobDTO dto = new JobDTO();
        dto.setId(j.getId());
        dto.setCompanyId(j.getCompanyId());
        dto.setTitle(j.getTitle());
        dto.setStatus(j.getStatus());
        return Result.ok(dto);
    }
}
