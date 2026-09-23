package com.ll.hirehub.auth.controller;

import com.ll.hirehub.auth.dto.LoginRequest;
import com.ll.hirehub.auth.dto.RealNameRequest;
import com.ll.hirehub.auth.dto.RefreshRequest;
import com.ll.hirehub.auth.dto.RegisterRequest;
import com.ll.hirehub.auth.service.AuthService;
import com.ll.hirehub.auth.vo.LoginVO;
import com.ll.hirehub.auth.vo.MeIdentitiesVO;
import com.ll.hirehub.auth.vo.UserInfoVO;
import com.ll.hirehub.common.result.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 鉴权接口（见架构文档 §7）
 * /register、/login、/refresh 公开；/me、/real-name、/logout 依赖网关注入的 X-User-Id
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

    /**
     * 刷新 token（D-02）：accessToken 过期后用 refreshToken 静默换新，前端无感。
     * 该接口在网关白名单里——access token 已过期，不能要求它有效。
     */
    @PostMapping("/refresh")
    public Result<LoginVO> refresh(@Valid @RequestBody RefreshRequest req) {
        return Result.ok(authService.refresh(req.getRefreshToken()));
    }

    /** 登出（D-02）：access jti 进黑名单 + refresh token 作废，之后旧 accessToken 立即失效 */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader("X-User-Id") Long userId,
                              @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                              @RequestBody(required = false) RefreshRequest req) {
        authService.logout(userId, authorization, req == null ? null : req.getRefreshToken());
        return Result.ok();
    }

    @GetMapping("/me")
    public Result<UserInfoVO> me(@RequestHeader("X-User-Id") Long userId) {
        return Result.ok(authService.me(userId));
    }

    /**
     * 身份总览（D-06）：全局角色 + 多企业身份，前端身份切换器的唯一数据源。
     * 只读接口——切换身份不调后端，只改前端路由与 X-Company-Id。
     */
    @GetMapping("/me/identities")
    public Result<MeIdentitiesVO> identities(@RequestHeader("X-User-Id") Long userId) {
        return Result.ok(authService.identities(userId));
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
