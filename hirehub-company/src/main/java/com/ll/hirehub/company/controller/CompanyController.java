package com.ll.hirehub.company.controller;

import com.ll.hirehub.common.result.Result;
import com.ll.hirehub.company.dto.CreateCompanyRequest;
import com.ll.hirehub.company.dto.JoinRequest;
import com.ll.hirehub.company.dto.SubmitVerifyRequest;
import com.ll.hirehub.company.entity.Company;
import com.ll.hirehub.company.entity.CompanyMember;
import com.ll.hirehub.company.service.CompanyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/company")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyService companyService;

    @PostMapping
    public Result<Long> create(@RequestHeader("X-User-Id") Long userId,
                               @Valid @RequestBody CreateCompanyRequest req) {
        return Result.ok(companyService.create(userId, req));
    }

    @GetMapping("/{id}")
    public Result<Company> get(@PathVariable Long id) {
        return Result.ok(companyService.get(id));
    }

    @PostMapping("/{id}/verify")
    public Result<Void> submitVerify(@RequestHeader("X-User-Id") Long userId,
                                     @PathVariable Long id,
                                     @Valid @RequestBody SubmitVerifyRequest req) {
        companyService.submitVerify(userId, id, req);
        return Result.ok();
    }

    @GetMapping("/{id}/verify-status")
    public Result<Integer> verifyStatus(@PathVariable Long id) {
        return Result.ok(companyService.get(id).getVerifyStatus());
    }

    @GetMapping("/{id}/members")
    public Result<List<CompanyMember>> members(@PathVariable Long id) {
        return Result.ok(companyService.members(id));
    }

    @PostMapping("/{id}/invite-code")
    public Result<String> inviteCode(@RequestHeader("X-User-Id") Long userId,
                                     @PathVariable Long id) {
        return Result.ok(companyService.generateInviteCode(userId, id));
    }

    @PostMapping("/join")
    public Result<Void> join(@RequestHeader("X-User-Id") Long userId,
                             @Valid @RequestBody JoinRequest req) {
        companyService.join(userId, req);
        return Result.ok();
    }
}
