package com.ll.hirehub.job.controller;

import com.ll.hirehub.common.result.Result;
import com.ll.hirehub.job.dto.CreateJobRequest;
import com.ll.hirehub.job.entity.Job;
import com.ll.hirehub.job.service.JobService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
