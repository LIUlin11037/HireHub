package com.ll.hirehub.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ll.hirehub.api.CompanyClient;
import com.ll.hirehub.api.dto.CompanyMemberDTO;
import com.ll.hirehub.auth.dto.LoginRequest;
import com.ll.hirehub.auth.dto.RealNameRequest;
import com.ll.hirehub.auth.dto.RegisterRequest;
import com.ll.hirehub.auth.entity.SysRole;
import com.ll.hirehub.auth.entity.SysUser;
import com.ll.hirehub.auth.entity.SysUserRole;
import com.ll.hirehub.auth.mapper.SysRoleMapper;
import com.ll.hirehub.auth.mapper.SysUserMapper;
import com.ll.hirehub.auth.mapper.SysUserRoleMapper;
import com.ll.hirehub.auth.vo.LoginVO;
import com.ll.hirehub.auth.vo.MeIdentitiesVO;
import com.ll.hirehub.auth.vo.UserInfoVO;
import com.ll.hirehub.common.exception.BusinessException;
import com.ll.hirehub.common.result.ResultCode;
import com.ll.hirehub.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final RealNameVerifier realNameVerifier;
    private final TokenStore tokenStore;
    private final CompanyClient companyClient;

    @Transactional(rollbackFor = Exception.class)
    public void register(RegisterRequest req) {
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, req.getUsername()));
        if (count > 0) {
            throw new BusinessException("用户名已存在");
        }

        SysUser user = new SysUser();
        user.setUsername(req.getUsername());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setPhone(req.getPhone());
        user.setNickname(req.getNickname() != null ? req.getNickname() : req.getUsername());
        user.setStatus(1);
        user.setRealNameStatus(0);
        userMapper.insert(user);

        // 注册接口硬编码只绑定 SEEKER，忽略客户端传入的任何角色字段（见 D-05）
        SysRole seeker = roleMapper.selectOne(
                new LambdaQueryWrapper<SysRole>().eq(SysRole::getCode, "SEEKER"));
        if (seeker == null) {
            throw new BusinessException("角色初始化缺失，请先执行 sql/auth-schema.sql");
        }
        SysUserRole ur = new SysUserRole();
        ur.setUserId(user.getId());
        ur.setRoleId(seeker.getId());
        userRoleMapper.insert(ur);
    }

    public LoginVO login(LoginRequest req) {
        SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, req.getUsername()));
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException("账号已被禁用");
        }

        List<String> roles = roleMapper.selectRoleCodesByUserId(user.getId());

        user.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(user);

        return issue(user, roles);
    }

    /**
     * 用 refresh token 换一对新 token（见 D-02）。
     * <p>
     * <b>一次性轮换</b>：旧的 refresh token 立即作废。若同一个 refresh token 被第二次使用，
     * 说明它可能已泄露（正常客户端拿到新 token 后不会再拿旧的来换），
     * 此时把该用户<b>全部</b> refresh token 作废，强制重新登录。
     */
    public LoginVO refresh(String refreshToken) {
        Claims claims;
        try {
            claims = jwtUtil.parseToken(refreshToken);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.TOKEN_INVALID);
        }
        if (!jwtUtil.isRefreshToken(claims)) {
            throw new BusinessException(ResultCode.TOKEN_INVALID.getCode(), "凭证类型错误，请使用 refreshToken");
        }

        Long userId = Long.valueOf(claims.getSubject());
        String jti = claims.getId();
        if (!tokenStore.isRefreshValid(userId, jti)) {
            tokenStore.revokeAllRefresh(userId);   // 重放 → 凭证链整体失效
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "刷新凭证已失效，请重新登录");
        }

        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException("账号已被禁用");
        }

        tokenStore.revokeRefresh(userId, jti);
        List<String> roles = roleMapper.selectRoleCodesByUserId(userId);
        return issue(user, roles);
    }

    /**
     * 登出（见 D-02）：把 access token 的 jti 写进 Redis 黑名单（TTL = 剩余有效期），
     * 同时作废 refresh token，使"登出"真正具备撤销语义（而不是前端把 token 丢掉）。
     * <p>
     * 不传 refreshToken 时按<b>撤销该用户全部</b> refresh token 处理——宁可多撤销，不留后门。
     */
    public void logout(Long userId, String authorization, String refreshToken) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            try {
                Claims claims = jwtUtil.parseToken(authorization.substring(7));
                tokenStore.blacklistAccess(claims.getId(), jwtUtil.getRemainingSeconds(claims));
            } catch (Exception e) {
                // token 本身无效/已过期，没有可拉黑的东西，不影响登出成功
                log.debug("登出时 access token 无法解析（可能已过期）: {}", e.getMessage());
            }
        }

        if (refreshToken != null && !refreshToken.isBlank()) {
            try {
                Claims claims = jwtUtil.parseToken(refreshToken);
                if (jwtUtil.isRefreshToken(claims)) {
                    tokenStore.revokeRefresh(Long.valueOf(claims.getSubject()), claims.getId());
                    return;
                }
            } catch (Exception e) {
                log.debug("登出时 refresh token 无法解析: {}", e.getMessage());
            }
        }
        tokenStore.revokeAllRefresh(userId);
    }

    /**
     * 身份总览（见 D-06）：全局角色 + 该账号在各企业的身份。
     * 只读、幂等——前端靠它渲染身份切换器，服务端不保存"当前身份"。
     */
    public MeIdentitiesVO identities(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        MeIdentitiesVO vo = new MeIdentitiesVO();
        vo.setGlobalRoles(roleMapper.selectRoleCodesByUserId(userId));

        List<CompanyMemberDTO> companies = companyClient.listUserCompanies(userId).getData();
        vo.setCompanies(companies == null ? List.of()
                : companies.stream().map(this::toCompanyIdentity).toList());
        return vo;
    }

    private MeIdentitiesVO.CompanyIdentity toCompanyIdentity(CompanyMemberDTO dto) {
        MeIdentitiesVO.CompanyIdentity identity = new MeIdentitiesVO.CompanyIdentity();
        identity.setCompanyId(dto.getCompanyId());
        identity.setCompanyName(dto.getCompanyName());
        identity.setRole(dto.getRole());
        identity.setVerifyStatusCode(dto.getVerifyStatus());
        identity.setVerifyStatus(verifyStatusText(dto.getVerifyStatus()));
        return identity;
    }

    private String verifyStatusText(Integer code) {
        if (code == null) {
            return "未知";
        }
        return switch (code) {
            case 0 -> "待审核";
            case 1 -> "通过";
            case 2 -> "驳回";
            case 3 -> "已撤销";
            default -> "未知";
        };
    }

    /** 签发一对 token 并登记 refresh token（白名单） */
    private LoginVO issue(SysUser user, List<String> roles) {
        String accessJti = newJti();
        String refreshJti = newJti();
        String accessToken = jwtUtil.generateAccessToken(user.getId(), roles, accessJti);
        String refreshToken = jwtUtil.generateRefreshToken(user.getId(), refreshJti);
        tokenStore.storeRefresh(user.getId(), refreshJti, jwtUtil.getRefreshExpireSeconds());

        LoginVO vo = new LoginVO();
        vo.setAccessToken(accessToken);
        vo.setRefreshToken(refreshToken);
        vo.setExpiresIn(jwtUtil.getAccessExpireSeconds());
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setRoles(roles);
        return vo;
    }

    private String newJti() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public UserInfoVO me(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        List<String> roles = roleMapper.selectRoleCodesByUserId(userId);
        UserInfoVO vo = new UserInfoVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setPhone(user.getPhone());
        vo.setEmail(user.getEmail());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setRealNameStatus(user.getRealNameStatus());
        vo.setRoles(roles);
        return vo;
    }

    @Transactional(rollbackFor = Exception.class)
    public void realName(Long userId, RealNameRequest req) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        // 身份证校验位真算法 + 人证比对 Mock
        realNameVerifier.verify(req.getRealName(), req.getIdCard());

        user.setRealName(req.getRealName());
        user.setIdCardNo(req.getIdCard());
        user.setRealNameStatus(1);
        user.setRealNameTime(LocalDateTime.now());
        userMapper.updateById(user);
    }

    public Integer realNameStatus(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        return user.getRealNameStatus();
    }
}
