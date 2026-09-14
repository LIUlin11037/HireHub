package com.ll.hirehub.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.ll.hirehub.auth.vo.UserInfoVO;
import com.ll.hirehub.common.exception.BusinessException;
import com.ll.hirehub.common.result.ResultCode;
import com.ll.hirehub.common.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final RealNameVerifier realNameVerifier;

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
        String jti = UUID.randomUUID().toString().replace("-", "");
        String token = jwtUtil.generateAccessToken(user.getId(), roles, jti);

        user.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(user);

        LoginVO vo = new LoginVO();
        vo.setAccessToken(token);
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setRoles(roles);
        return vo;
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
