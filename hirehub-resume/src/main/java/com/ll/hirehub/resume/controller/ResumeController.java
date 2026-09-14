package com.ll.hirehub.resume.controller;

import com.ll.hirehub.common.result.Result;
import com.ll.hirehub.resume.dto.SavePreferenceRequest;
import com.ll.hirehub.resume.dto.SaveResumeRequest;
import com.ll.hirehub.resume.entity.JobPreference;
import com.ll.hirehub.resume.entity.Resume;
import com.ll.hirehub.resume.service.ResumeService;
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

@RestController
@RequestMapping("/api/resume")
@RequiredArgsConstructor
public class ResumeController {

    private final ResumeService resumeService;

    @PostMapping
    public Result<Long> create(@RequestHeader("X-User-Id") Long userId,
                               @Valid @RequestBody SaveResumeRequest req) {
        return Result.ok(resumeService.create(userId, req));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@RequestHeader("X-User-Id") Long userId,
                               @PathVariable Long id,
                               @Valid @RequestBody SaveResumeRequest req) {
        resumeService.update(userId, id, req);
        return Result.ok();
    }

    @GetMapping("/{id}")
    public Result<Resume> get(@RequestHeader("X-User-Id") Long userId, @PathVariable Long id) {
        return Result.ok(resumeService.get(userId, id));
    }

    @GetMapping("/mine")
    public Result<List<Resume>> mine(@RequestHeader("X-User-Id") Long userId) {
        return Result.ok(resumeService.mine(userId));
    }

    @GetMapping("/preference")
    public Result<JobPreference> getPreference(@RequestHeader("X-User-Id") Long userId) {
        return Result.ok(resumeService.getPreference(userId));
    }

    @PutMapping("/preference")
    public Result<Void> savePreference(@RequestHeader("X-User-Id") Long userId,
                                       @RequestBody SavePreferenceRequest req) {
        resumeService.savePreference(userId, req);
        return Result.ok();
    }
}
