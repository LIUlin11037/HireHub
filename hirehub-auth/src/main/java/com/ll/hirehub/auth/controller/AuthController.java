package com.ll.hirehub.auth.controller;

import com.ll.hirehub.auth.dto.LoginRequest;
import com.ll.hirehub.auth.dto.RealNameRequest;
import com.ll.hirehub.auth.dto.RegisterRequest;
import com.ll.hirehub.auth.service.AuthService;
import com.ll.hirehub.auth.vo.LoginVO;
import com.ll.hirehub.auth.vo.UserInfoVO;
import com.ll.hirehub.common.result.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 鉴权接口（见架构文档 §7）
 * /register、/login 公开；/me、/real-name 依赖网关注入的 X-User-Id
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterRequest req) {
        authService.register(req);
        return Result.ok();
    }

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginRequest req) {
        return Result.ok(authService.login(req));
    }

    @GetMapping("/me")
    public Result<UserInfoVO> me(@RequestHeader("X-User-Id") Long userId) {
        return Result.ok(authService.me(userId));
    }

    @PostMapping("/real-name")
    public Result<Void> realName(@RequestHeader("X-User-Id") Long userId,
                                 @Valid @RequestBody RealNameRequest req) {
        authService.realName(userId, req);
        return Result.ok();
    }

    @GetMapping("/real-name/status")
    public Result<Integer> realNameStatus(@RequestHeader("X-User-Id") Long userId) {
        return Result.ok(authService.realNameStatus(userId));
    }
}
