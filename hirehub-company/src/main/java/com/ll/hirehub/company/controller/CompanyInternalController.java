package com.ll.hirehub.company.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ll.hirehub.api.dto.CompanyMemberDTO;
import com.ll.hirehub.common.result.Result;
import com.ll.hirehub.company.entity.Company;
import com.ll.hirehub.company.entity.CompanyMember;
import com.ll.hirehub.company.mapper.CompanyMapper;
import com.ll.hirehub.company.mapper.CompanyMemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 内部接口（服务间 Feign 调用，不走网关，/internal 前缀与 /api 隔离）
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class CompanyInternalController {

    private final CompanyMapper companyMapper;
    private final CompanyMemberMapper memberMapper;

    @GetMapping("/member")
    public Result<CompanyMemberDTO> getMember(@RequestParam Long companyId, @RequestParam Long userId) {
        CompanyMember m = memberMapper.selectOne(new LambdaQueryWrapper<CompanyMember>()
                .eq(CompanyMember::getCompanyId, companyId)
                .eq(CompanyMember::getUserId, userId));
        if (m == null) {
            return Result.ok(null);
        }
        CompanyMemberDTO dto = new CompanyMemberDTO();
        dto.setCompanyId(m.getCompanyId());
        dto.setUserId(m.getUserId());
        dto.setRole(m.getRole());
        dto.setStatus(m.getStatus());
        return Result.ok(dto);
    }

    /** 企业在职成员的用户 ID 列表（投递状态变更要通知「所有 HR」，见 §6.2 通知映射） */
    @GetMapping("/member-ids")
    public Result<List<Long>> listMemberUserIds(@RequestParam Long companyId) {
        List<CompanyMember> members = memberMapper.selectList(new LambdaQueryWrapper<CompanyMember>()
                .eq(CompanyMember::getCompanyId, companyId)
                .eq(CompanyMember::getStatus, 1));
        return Result.ok(members.stream().map(CompanyMember::getUserId).distinct().toList());
    }

    @GetMapping("/{id}/verify-status")
    public Result<Integer> getVerifyStatus(@PathVariable Long id) {
        Company c = companyMapper.selectById(id);
        return Result.ok(c == null ? null : c.getVerifyStatus());
    }
}
