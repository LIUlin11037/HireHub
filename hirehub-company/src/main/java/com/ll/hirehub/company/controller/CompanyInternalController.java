package com.ll.hirehub.company.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ll.hirehub.api.dto.CompanyMemberDTO;
import com.ll.hirehub.common.result.Result;
import com.ll.hirehub.company.entity.Company;
import com.ll.hirehub.company.entity.CompanyMember;
import com.ll.hirehub.company.mapper.CompanyMapper;
import com.ll.hirehub.company.mapper.CompanyMemberMapper;
import com.ll.hirehub.company.service.CompanyMemberCache;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
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
    private final CompanyMemberCache memberCache;

    /**
     * 查某人在某企业的成员关系（无则 data=null）。
     * <p>
     * 这是所有"水平越权"校验的公共入口（投递 / 职位 / 人才库都要过这里），
     * 所以加了一层 cache-aside（见 D-06）；缓存未命中就回落 DB 并回填。
     */
    @GetMapping("/member")
    public Result<CompanyMemberDTO> getMember(@RequestParam Long companyId, @RequestParam Long userId) {
        CompanyMemberDTO cached = memberCache.get(companyId, userId);
        if (cached != null) {
            // status != 1 且无角色 = 否定缓存命中，确认不是在职成员
            boolean confirmedAbsent = cached.getRole() == null;
            return Result.ok(confirmedAbsent ? null : cached);
        }

        CompanyMember m = memberMapper.selectOne(new LambdaQueryWrapper<CompanyMember>()
                .eq(CompanyMember::getCompanyId, companyId)
                .eq(CompanyMember::getUserId, userId));
        CompanyMemberDTO dto = toDto(m, companyMapper.selectById(companyId));
        memberCache.put(dto == null ? absent(companyId, userId) : dto);
        return Result.ok(dto);
    }

    /**
     * 查某用户加入的全部企业（见 D-06）。
     * <p>
     * {@code GET /api/auth/me/identities} 的唯一数据源——它回答的是
     * "这个账号能以便宜哪些企业的什么角色出现"，切换身份只改前端路由与 X-Company-Id，不动服务端状态。
     */
    @GetMapping("/user-companies")
    public Result<List<CompanyMemberDTO>> listUserCompanies(@RequestParam Long userId) {
        List<CompanyMember> members = memberMapper.selectList(new LambdaQueryWrapper<CompanyMember>()
                .eq(CompanyMember::getUserId, userId)
                .eq(CompanyMember::getStatus, 1));
        List<CompanyMemberDTO> result = new ArrayList<>();
        for (CompanyMember m : members) {
            result.add(toDto(m, companyMapper.selectById(m.getCompanyId())));
        }
        return Result.ok(result);
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

    private CompanyMemberDTO toDto(CompanyMember m, Company company) {
        if (m == null) {
            return null;
        }
        CompanyMemberDTO dto = new CompanyMemberDTO();
        dto.setCompanyId(m.getCompanyId());
        dto.setUserId(m.getUserId());
        dto.setRole(m.getRole());
        dto.setStatus(m.getStatus());
        dto.setCompanyName(company == null ? null : company.getName());
        dto.setVerifyStatus(company == null ? null : company.getVerifyStatus());
        return dto;
    }

    private CompanyMemberDTO absent(Long companyId, Long userId) {
        CompanyMemberDTO dto = new CompanyMemberDTO();
        dto.setCompanyId(companyId);
        dto.setUserId(userId);
        dto.setStatus(0);
        return dto;
    }
}
