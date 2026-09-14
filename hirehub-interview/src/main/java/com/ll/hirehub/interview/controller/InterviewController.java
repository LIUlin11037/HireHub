package com.ll.hirehub.interview.controller;

import com.ll.hirehub.common.result.Result;
import com.ll.hirehub.interview.dto.CancelRequest;
import com.ll.hirehub.interview.dto.CreateInterviewRequest;
import com.ll.hirehub.interview.dto.FeedbackRequest;
import com.ll.hirehub.interview.dto.RescheduleRequest;
import com.ll.hirehub.interview.entity.Interview;
import com.ll.hirehub.interview.service.InterviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 面试接口（见 §7 / D-28）：面试取消/拒绝不回退投递状态，投递仍停在 INTERVIEW。
 */
@RestController
@RequestMapping("/api/interview")
@RequiredArgsConstructor
public class InterviewController {

    private final InterviewService interviewService;

    @PostMapping
    public Result<Long> create(@RequestHeader("X-User-Id") Long userId,
                               @Valid @RequestBody CreateInterviewRequest req) {
        return Result.ok(interviewService.create(userId, req));
    }

    @GetMapping("/mine")
    public Result<List<Interview>> mine(@RequestHeader("X-User-Id") Long userId) {
        return Result.ok(interviewService.mine(userId));
    }

    @GetMapping("/{id}")
    public Result<Interview> get(@RequestHeader("X-User-Id") Long userId, @PathVariable Long id) {
        return Result.ok(interviewService.get(userId, id));
    }

    @PutMapping("/{id}/confirm")
    public Result<Void> confirm(@RequestHeader("X-User-Id") Long userId, @PathVariable Long id) {
        interviewService.confirm(userId, id);
        return Result.ok();
    }

    @PutMapping("/{id}/reject")
    public Result<Void> reject(@RequestHeader("X-User-Id") Long userId, @PathVariable Long id) {
        interviewService.reject(userId, id);
        return Result.ok();
    }

    @PutMapping("/{id}/cancel")
    public Result<Void> cancel(@RequestHeader("X-User-Id") Long userId,
                               @PathVariable Long id,
                               @RequestBody CancelRequest req) {
        interviewService.cancel(userId, id, req);
        return Result.ok();
    }

    @PutMapping("/{id}/reschedule")
    public Result<Void> reschedule(@RequestHeader("X-User-Id") Long userId,
                                   @PathVariable Long id,
                                   @RequestBody RescheduleRequest req) {
        interviewService.reschedule(userId, id, req);
        return Result.ok();
    }

    @PutMapping("/{id}/feedback")
    public Result<Void> feedback(@RequestHeader("X-User-Id") Long userId,
                                 @PathVariable Long id,
                                 @RequestBody FeedbackRequest req) {
        interviewService.feedback(userId, id, req);
        return Result.ok();
    }
}
