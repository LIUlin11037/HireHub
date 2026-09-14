package com.ll.hirehub.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ll.hirehub.auth.entity.SysRole;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface SysRoleMapper extends BaseMapper<SysRole> {

    /** 查某用户的全局角色 code（如 SEEKER / PLATFORM_ADMIN） */
    @Select("SELECT r.code FROM sys_role r " +
            "JOIN sys_user_role ur ON r.id = ur.role_id " +
            "WHERE ur.user_id = #{userId} AND r.deleted = 0")
    List<String> selectRoleCodesByUserId(Long userId);
}
