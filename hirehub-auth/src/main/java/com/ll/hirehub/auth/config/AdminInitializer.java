package com.ll.hirehub.auth.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ll.hirehub.auth.entity.SysRole;
import com.ll.hirehub.auth.entity.SysUser;
import com.ll.hirehub.auth.entity.SysUserRole;
import com.ll.hirehub.auth.mapper.SysRoleMapper;
import com.ll.hirehub.auth.mapper.SysUserMapper;
import com.ll.hirehub.auth.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 启动时幂等初始化管理员账号（见 D-05）：
 * PLATFORM_ADMIN 只能由初始化产生，注册接口永远创建不出管理员。
 * <p>
 * 密码通过环境变量 HIREHUB_ADMIN_PASSWORD 注入；开发默认 admin123 仅在
 * {@code hirehub.security.allow-dev-defaults=true} 时可用，否则 fail-fast（审计 S-05）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminInitializer implements CommandLineRunner {

    /** 开发默认密码（审计 S-05）：仅本地开发可用，且必须显式开启 allow-dev-defaults */
    private static final String DEV_PASSWORD = "admin123";

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;

    @Value("${hirehub.admin.username:admin}")
    private String adminUsername;

    @Value("${hirehub.admin.password:}")
    private String adminPassword;

    @Value("${hirehub.security.allow-dev-defaults:false}")
    private boolean allowDevDefaults;

    @Override
    public void run(String... args) {
        SysUser admin = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, adminUsername));
        if (admin == null) {
            String password = (adminPassword == null || adminPassword.isBlank())
                    ? DEV_PASSWORD : adminPassword;
            if (DEV_PASSWORD.equals(password)) {
                if (!allowDevDefaults) {
                    throw new IllegalStateException(
                            "未注入管理员初始密码（HIREHUB_ADMIN_PASSWORD），且未开启开发默认"
                                    + "（hirehub.security.allow-dev-defaults=true），拒绝以弱口令初始化管理员");
                }
                log.warn("正在使用开发默认管理员密码（allow-dev-defaults=true），仅限本地开发，严禁生产使用");
            }
            admin = new SysUser();
            admin.setUsername(adminUsername);
            admin.setPassword(passwordEncoder.encode(password));
            admin.setNickname("平台管理员");
            admin.setStatus(1);
            admin.setRealNameStatus(0);
            userMapper.insert(admin);
        }

        SysRole role = roleMapper.selectOne(
                new LambdaQueryWrapper<SysRole>().eq(SysRole::getCode, "PLATFORM_ADMIN"));
        if (role != null) {
            Long count = userRoleMapper.selectCount(new LambdaQueryWrapper<SysUserRole>()
                    .eq(SysUserRole::getUserId, admin.getId())
                    .eq(SysUserRole::getRoleId, role.getId()));
            if (count == 0) {
                SysUserRole ur = new SysUserRole();
                ur.setUserId(admin.getId());
                ur.setRoleId(role.getId());
                userRoleMapper.insert(ur);
            }
        }
    }
}
