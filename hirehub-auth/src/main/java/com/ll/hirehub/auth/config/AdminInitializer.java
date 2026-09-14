package com.ll.hirehub.auth.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ll.hirehub.auth.entity.SysRole;
import com.ll.hirehub.auth.entity.SysUser;
import com.ll.hirehub.auth.entity.SysUserRole;
import com.ll.hirehub.auth.mapper.SysRoleMapper;
import com.ll.hirehub.auth.mapper.SysUserMapper;
import com.ll.hirehub.auth.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 启动时幂等初始化管理员账号（见 D-05）：
 * PLATFORM_ADMIN 只能由初始化产生，注册接口永远创建不出管理员。
 * 密码通过环境变量 HIREHUB_ADMIN_PASSWORD 覆盖，默认 admin123。
 */
@Component
@RequiredArgsConstructor
public class AdminInitializer implements CommandLineRunner {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;

    @Value("${hirehub.admin.username:admin}")
    private String adminUsername;

    @Value("${hirehub.admin.password:admin123}")
    private String adminPassword;

    @Override
    public void run(String... args) {
        SysUser admin = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, adminUsername));
        if (admin == null) {
            admin = new SysUser();
            admin.setUsername(adminUsername);
            admin.setPassword(passwordEncoder.encode(adminPassword));
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
