package com.ll.hirehub.job.controller;

import com.ll.hirehub.common.result.Result;
import com.ll.hirehub.job.dto.CreateJobRequest;
import com.ll.hirehub.job.entity.Job;
import com.ll.hirehub.job.service.JobService;
import com.ll.hirehub.job.vo.JobPageVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/job")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;

    @PostMapping("/draft")
    public Result<Long> draft(@RequestHeader("X-User-Id") Long userId,
                              @Valid @RequestBody CreateJobRequest req) {
        return Result.ok(jobService.draft(userId, req));
    }

    @PostMapping("/{id}/publish")
    public Result<Void> publish(@RequestHeader("X-User-Id") Long userId, @PathVariable Long id) {
        jobService.publish(userId, id);
        return Result.ok();
    }

    @PostMapping("/{id}/offline")
    public Result<Void> offline(@RequestHeader("X-User-Id") Long userId, @PathVariable Long id) {
        jobService.offline(userId, id);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@RequestHeader("X-User-Id") Long userId, @PathVariable Long id) {
        jobService.delete(userId, id);
        return Result.ok();
    }

    @GetMapping("/{id}")
    public Result<Job> get(@PathVariable Long id) {
        return Result.ok(jobService.get(id));
    }

    /**
     * HR 视角：本企业的职位列表（**含草稿与已下线**，见 D-41）。
     * <p>
     * 用 {@code X-Company-Id} 而不是查询参数，与 delivery / resume 的 HR 侧接口保持一致
     * （企业的"当前身份"由这个头表达，网关原样转发、不注入，服务端再校验成员关系）。
     *
     * @param status 可选：0 草稿 / 1 招聘中 / 2 已下线；不传则返回全部
     */
    @GetMapping("/mine")
    public Result<JobPageVO> mine(@RequestHeader("X-User-Id") Long userId,
                                  @RequestHeader("X-Company-Id") Long companyId,
                                  @RequestParam(required = false) Integer status,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return Result.ok(jobService.mine(companyId, userId, status, page, size));
    }
}
