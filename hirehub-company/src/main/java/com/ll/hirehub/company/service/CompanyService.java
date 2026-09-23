package com.ll.hirehub.company.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ll.hirehub.api.AuthClient;
import com.ll.hirehub.common.exception.BusinessException;
import com.ll.hirehub.common.result.ResultCode;
import com.ll.hirehub.common.util.IdCardUtil;
import com.ll.hirehub.company.dto.AdminVerifyRequest;
import com.ll.hirehub.company.dto.AuthorizeRequest;
import com.ll.hirehub.company.dto.CreateCompanyRequest;
import com.ll.hirehub.company.dto.JoinRequest;
import com.ll.hirehub.company.dto.SubmitVerifyRequest;
import com.ll.hirehub.company.dto.SubmitVerifyResult;
import com.ll.hirehub.company.entity.Company;
import com.ll.hirehub.company.entity.CompanyAuthorization;
import com.ll.hirehub.company.entity.CompanyMember;
import com.ll.hirehub.company.entity.CompanyVerifyRecord;
import com.ll.hirehub.company.mapper.CompanyAuthorizationMapper;
import com.ll.hirehub.company.mapper.CompanyMapper;
import com.ll.hirehub.company.mapper.CompanyMemberMapper;
import com.ll.hirehub.company.mapper.CompanyVerifyRecordMapper;
import com.ll.hirehub.company.search.CompanySyncEvent;
import com.ll.hirehub.company.util.CreditCodeUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CompanyService {

    private final CompanyMapper companyMapper;
    private final CompanyMemberMapper memberMapper;
    private final CompanyVerifyRecordMapper recordMapper;
    private final CompanyAuthorizationMapper authorizationMapper;
    private final CompanyVerifier companyVerifier;
    private final RiskScorer riskScorer;
    private final AuthClient authClient;
    private final ApplicationEventPublisher eventPublisher;
    private final CompanyMemberCache memberCache;

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
        evictMemberAfterCommit(company.getId(), userId);
        eventPublisher.publishEvent(new CompanySyncEvent(company.getId()));   // 同步 company_index
        return company.getId();
    }

    public Company get(Long companyId) {
        return requireCompany(companyId);
    }

    /**
     * 提交企业认证 —— 三层核验（见 D-24 / §6.7）。
     * <pre>
     *   第一层 个人实名（auth）：未实名直接拒绝
     *   基础数据核验（D-18）：信用代码真算法 + 企业三要素（Mock）+ 经营状态
     *   第二层 风险评分：低风险 → 自动通过；中风险 → 走第三层
     *   第三层 法人授权令牌闭环：生成一次性令牌，等法人本人完成实名并授权
     * </pre>
     * 任何一层的结果都写入 company_verify_record 留档。
     */
    @Transactional(rollbackFor = Exception.class)
    public SubmitVerifyResult submitVerify(Long userId, Long companyId, SubmitVerifyRequest req) {
        Company company = requireCompany(companyId);
        requireOwner(companyId, userId);

        // 第一层：个人实名（不信任前端，向 auth 核实）
        Integer realNameStatus = authClient.getRealNameStatus(userId).getData();
        if (realNameStatus == null || realNameStatus != 1) {
            throw new BusinessException("请先完成个人实名认证");
        }

        // 基础数据核验（D-18）
        CompanyVerifyResult result = companyVerifier.verify(
                company.getName(), company.getCreditCode(), req.getLegalPersonName());

        SubmitVerifyResult vo = new SubmitVerifyResult();

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

        // 基础核验不通过（含注销 / 吊销）→ 直接驳回
        if (!result.isValid()) {
            company.setVerifyStatus(2);
            company.setVerifyRemark(result.getMessage());
            companyMapper.updateById(company);
            vo.setRiskLevel("REJECTED");
            return vo;
        }

        company.setLegalPersonName(req.getLegalPersonName());
        company.setBusinessStatus(result.getBusinessStatus());

        // 第二层：风险评分（简单加权，见 §6.7）
        boolean hasLicense = req.getLicenseUrl() != null && !req.getLicenseUrl().isBlank();
        String risk = riskScorer.score(true, hasLicense, hasEverRejected(companyId));
        company.setRiskLevel(risk);
        vo.setRiskLevel(risk);

        if (RiskScorer.LOW.equals(risk)) {
            // 低风险：材料齐全 + 已实名 → 自动通过
            company.setVerifyStatus(1);
            company.setVerifyTime(LocalDateTime.now());
            vo.setAutoApproved(true);
        } else {
            // 中风险：进第三层，生成法人授权令牌（企业保持待审核，等法人授权）
            company.setVerifyStatus(0);
            vo.setAuthorizationToken(createAuthorization(companyId, req.getLegalPersonName()));
        }
        companyMapper.updateById(company);
        eventPublisher.publishEvent(new CompanySyncEvent(companyId));
        return vo;
    }

    /**
     * 第三层：法人授权（**法人无需注册账号**，凭一次性令牌 + 实名完成授权）。
     * <p>
     * 与「姓名比对」的本质区别：重名率极高，姓名是弱证据；
     * 法人本人完成实名并点击同意才是强证据——授权来自法人行为，不是系统推断。
     */
    @Transactional(rollbackFor = Exception.class)
    public void authorize(AuthorizeRequest req) {
        CompanyAuthorization auth = authorizationMapper.selectOne(new LambdaQueryWrapper<CompanyAuthorization>()
                .eq(CompanyAuthorization::getToken, req.getToken()));
        if (auth == null || auth.getStatus() == null || auth.getStatus() != CompanyAuthorization.STATUS_PENDING) {
            throw new BusinessException("授权令牌无效或已使用");
        }
        if (auth.getExpireTime() != null && auth.getExpireTime().isBefore(LocalDateTime.now())) {
            auth.setStatus(CompanyAuthorization.STATUS_EXPIRED);
            authorizationMapper.updateById(auth);
            throw new BusinessException("授权令牌已过期");
        }
        // 法人实名：身份证校验位真算法（人证比对 Mock）
        if (!IdCardUtil.isValid(req.getIdCard())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "身份证号校验位错误");
        }

        auth.setStatus(CompanyAuthorization.STATUS_AUTHORIZED);
        auth.setLegalPersonRealName(req.getRealName());
        auth.setAuthorizeTime(LocalDateTime.now());
        authorizationMapper.updateById(auth);

        // 法人授权成功即视为企业通过认证
        Company company = requireCompany(auth.getCompanyId());
        company.setVerifyStatus(1);
        company.setVerifyTime(LocalDateTime.now());
        company.setVerifyRemark("法人已授权");
        companyMapper.updateById(company);

        CompanyVerifyRecord record = new CompanyVerifyRecord();
        record.setCompanyId(auth.getCompanyId());
        record.setCreditCode(company.getCreditCode());
        record.setCompanyName(company.getName());
        record.setLegalPersonName(req.getRealName());
        record.setVerifyChannel("LEGAL_AUTH");
        record.setResult("通过");
        record.setRemark("法人授权");
        recordMapper.insert(record);

        eventPublisher.publishEvent(new CompanySyncEvent(auth.getCompanyId()));
    }

    /** 生成法人授权令牌：随机、一次性、24 小时有效 */
    private String createAuthorization(Long companyId, String legalPersonName) {
        CompanyAuthorization auth = new CompanyAuthorization();
        auth.setCompanyId(companyId);
        auth.setToken(UUID.randomUUID().toString().replace("-", ""));
        auth.setLegalPersonName(legalPersonName);
        auth.setStatus(CompanyAuthorization.STATUS_PENDING);
        auth.setExpireTime(LocalDateTime.now().plusHours(24));
        authorizationMapper.insert(auth);
        return auth.getToken();
    }

    /** 同企业是否曾被驳回（风险评分维度之一） */
    private boolean hasEverRejected(Long companyId) {
        Long count = recordMapper.selectCount(new LambdaQueryWrapper<CompanyVerifyRecord>()
                .eq(CompanyVerifyRecord::getCompanyId, companyId)
                .eq(CompanyVerifyRecord::getResult, "驳回"));
        return count != null && count > 0;
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
        eventPublisher.publishEvent(new CompanySyncEvent(companyId));   // 认证状态变化 → 同步 company_index
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
        evictMemberAfterCommit(company.getId(), userId);
    }

    /**
     * 成员关系变化后失效缓存（见 D-06）。
     * <p>
     * <b>必须在事务提交后失效</b>：如果先删缓存、事务又回滚，并发读会把"旧值"重新填回缓存，
     * 结果缓存带着错误权限活到 TTL 结束——权限缓存的脏读就是水平越权。
     * 提交后删除只剩一个极小的窗口（读完旧值 → 提交 → 删除），配合短 TTL 可以接受；
     * 要彻底消除需延迟双删，本项目不值得为此增加复杂度。
     */
    private void evictMemberAfterCommit(Long companyId, Long userId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    memberCache.evict(companyId, userId);
                }
            });
        } else {
            memberCache.evict(companyId, userId);
        }
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
