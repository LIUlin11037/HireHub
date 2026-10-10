package com.ll.hirehub.job.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ll.hirehub.api.dto.IdNameDTO;
import com.ll.hirehub.api.dto.JobDTO;
import com.ll.hirehub.common.result.Result;
import com.ll.hirehub.job.entity.Job;
import com.ll.hirehub.job.mapper.JobMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    /**
     * 批量查职位标题（投递列表冗余 jobTitle 用，见 D-41）。
     * <p>
     * 一页 N 条投递只调这里 <b>一次</b>，而不是 N 次 {@code /internal/{id}}。
     * <p>
     * 路径 {@code /titles} 与 {@code /{id}} 共存：字面量路径在 Spring 的匹配优先级里高于变量，
     * 所以 {@code /internal/titles} 不会掉进 {@code /{id}} 去试图把 "titles" 转成 Long。
     */
    @GetMapping("/titles")
    public Result<List<IdNameDTO>> listTitles(@RequestParam List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Result.ok(List.of());
        }
        List<Job> jobs = jobMapper.selectList(new LambdaQueryWrapper<Job>()
                .select(Job::getId, Job::getTitle)
                .in(Job::getId, ids));
        return Result.ok(jobs.stream()
                .map(j -> new IdNameDTO(j.getId(), j.getTitle()))
                .toList());
    }
}
