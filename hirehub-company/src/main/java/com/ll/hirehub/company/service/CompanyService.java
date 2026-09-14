package com.ll.hirehub.company.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ll.hirehub.common.exception.BusinessException;
import com.ll.hirehub.common.result.ResultCode;
import com.ll.hirehub.company.dto.AdminVerifyRequest;
import com.ll.hirehub.company.dto.CreateCompanyRequest;
import com.ll.hirehub.company.dto.JoinRequest;
import com.ll.hirehub.company.dto.SubmitVerifyRequest;
import com.ll.hirehub.company.entity.Company;
import com.ll.hirehub.company.entity.CompanyMember;
import com.ll.hirehub.company.entity.CompanyVerifyRecord;
import com.ll.hirehub.company.mapper.CompanyMapper;
import com.ll.hirehub.company.mapper.CompanyMemberMapper;
import com.ll.hirehub.company.mapper.CompanyVerifyRecordMapper;
import com.ll.hirehub.company.util.CreditCodeUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CompanyService {

    private final CompanyMapper companyMapper;
    private final CompanyMemberMapper memberMapper;
    private final CompanyVerifyRecordMapper recordMapper;
    private final CompanyVerifier companyVerifier;

    /** 创建企业（创建者自动成为 OWNER，见 D-14 / §6.7） */
    @Transactional(rollbackFor = Exception.class)
    public Long create(Long userId, CreateCompanyRequest req) {
        if (!CreditCodeUtil.isValid(req.getCreditCode())) {
            throw new BusinessException("统一社会信用代码校验位错误");
        }
        Long count = companyMapper.selectCount(
                new LambdaQueryWrapper<Company>().eq(Company::getCreditCode, req.getCreditCode()));
        if (count > 0) {
            throw new BusinessException("该企业已入驻");
        }

        Company company = new Company();
        company.setName(req.getName());
        company.setCreditCode(req.getCreditCode());
        company.setIndustry(req.getIndustry());
        company.setScale(req.getScale());
        company.setCity(req.getCity());
        company.setAddress(req.getAddress());
        company.setDescription(req.getDescription());
        company.setVerifyStatus(0); // 待审核
        companyMapper.insert(company);

        CompanyMember member = new CompanyMember();
        member.setCompanyId(company.getId());
        member.setUserId(userId);
        member.setRole("OWNER");
        member.setIsLegalRep(0);
        member.setStatus(1);
        memberMapper.insert(member);
        return company.getId();
    }

    public Company get(Long companyId) {
        return requireCompany(companyId);
    }

    /** 提交企业认证：自动核验（Mock）+ 留档，等待管理员最终审核 */
    @Transactional(rollbackFor = Exception.class)
    public void submitVerify(Long userId, Long companyId, SubmitVerifyRequest req) {
        Company company = requireCompany(companyId);
        requireOwner(companyId, userId);

        CompanyVerifyResult result = companyVerifier.verify(
                company.getName(), company.getCreditCode(), req.getLegalPersonName());

        CompanyVerifyRecord record = new CompanyVerifyRecord();
        record.setCompanyId(companyId);
        record.setSubmitterId(userId);
        record.setCreditCode(company.getCreditCode());
        record.setCompanyName(company.getName());
        record.setLegalPersonName(req.getLegalPersonName());
        record.setLicenseUrl(req.getLicenseUrl());
        record.setVerifyChannel("MOCK");
        record.setResult(result.isValid() ? "通过" : "驳回");
        record.setRemark(result.getMessage());
        recordMapper.insert(record);

        if (!result.isValid()) {
            company.setVerifyStatus(2);
            company.setVerifyRemark(result.getMessage());
        } else {
            company.setLegalPersonName(req.getLegalPersonName());
            company.setBusinessStatus(result.getBusinessStatus());
            company.setVerifyStatus(0); // 自动核验通过，仍待管理员最终审核
        }
        companyMapper.updateById(company);
    }

    /** 管理员审核（见 D-05 / D-07），只处理待审核状态 */
    @Transactional(rollbackFor = Exception.class)
    public void adminVerify(Long operatorId, Long companyId, AdminVerifyRequest req) {
        Company company = requireCompany(companyId);
        if (company.getVerifyStatus() == null || company.getVerifyStatus() != 0) {
            throw new BusinessException("该企业不在待审核状态");
        }
        company.setVerifyStatus(Boolean.TRUE.equals(req.getApprove()) ? 1 : 2);
        company.setVerifyTime(LocalDateTime.now());
        if (!Boolean.TRUE.equals(req.getApprove())) {
            company.setVerifyRemark(req.getRemark());
        }
        companyMapper.updateById(company);

        CompanyVerifyRecord record = new CompanyVerifyRecord();
        record.setCompanyId(companyId);
        record.setOperatorId(operatorId);
        record.setCreditCode(company.getCreditCode());
        record.setCompanyName(company.getName());
        record.setVerifyChannel("MANUAL");
        record.setResult(Boolean.TRUE.equals(req.getApprove()) ? "通过" : "驳回");
        record.setRemark(req.getRemark());
        recordMapper.insert(record);
    }

    /** 生成 / 重置邀请码（仅 OWNER，见 D-19） */
    public String generateInviteCode(Long userId, Long companyId) {
        Company company = requireCompany(companyId);
        requireOwner(companyId, userId);
        String code = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        company.setInviteCode(code);
        company.setInviteCodeExpireTime(LocalDateTime.now().plusDays(7));
        companyMapper.updateById(company);
        return code;
    }

    /** 凭邀请码加入（成为 HR，见 D-19） */
    @Transactional(rollbackFor = Exception.class)
    public void join(Long userId, JoinRequest req) {
        Company company = companyMapper.selectOne(
                new LambdaQueryWrapper<Company>().eq(Company::getInviteCode, req.getInviteCode()));
        if (company == null) {
            throw new BusinessException("邀请码无效");
        }
        if (company.getInviteCodeExpireTime() == null
                || company.getInviteCodeExpireTime().isBefore(LocalDateTime.now())) {
            throw new BusinessException("邀请码已过期");
        }
        Long count = memberMapper.selectCount(new LambdaQueryWrapper<CompanyMember>()
                .eq(CompanyMember::getCompanyId, company.getId())
                .eq(CompanyMember::getUserId, userId));
        if (count > 0) {
            throw new BusinessException("已是该企业成员");
        }
        CompanyMember member = new CompanyMember();
        member.setCompanyId(company.getId());
        member.setUserId(userId);
        member.setRole("HR");
        member.setIsLegalRep(0);
        member.setStatus(1);
        memberMapper.insert(member);
    }

    public List<CompanyMember> members(Long companyId) {
        return memberMapper.selectList(new LambdaQueryWrapper<CompanyMember>()
                .eq(CompanyMember::getCompanyId, companyId)
                .eq(CompanyMember::getStatus, 1));
    }

    public List<Company> listPending() {
        return companyMapper.selectList(
                new LambdaQueryWrapper<Company>().eq(Company::getVerifyStatus, 0));
    }

    private Company requireCompany(Long id) {
        Company company = companyMapper.selectById(id);
        if (company == null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        return company;
    }

    private void requireOwner(Long companyId, Long userId) {
        Long count = memberMapper.selectCount(new LambdaQueryWrapper<CompanyMember>()
                .eq(CompanyMember::getCompanyId, companyId)
                .eq(CompanyMember::getUserId, userId)
                .eq(CompanyMember::getRole, "OWNER")
                .eq(CompanyMember::getStatus, 1));
        if (count == 0) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "仅企业 OWNER 可操作");
        }
    }
}
