package com.ll.hirehub.company.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ll.hirehub.common.mq.MqConst;
import com.ll.hirehub.common.mq.MqMessage;
import com.ll.hirehub.common.mq.MqPayload;
import com.ll.hirehub.company.entity.Company;
import com.ll.hirehub.company.entity.CompanyVerifyRecord;
import com.ll.hirehub.company.mapper.CompanyMapper;
import com.ll.hirehub.company.mapper.CompanyVerifyRecordMapper;
import com.ll.hirehub.company.search.CompanySyncEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 企业认证撤销的事务执行器（审计 F-05）。
 * <p>
 * 此前 {@code revoke} 写在 {@link CompanyRecheckService} 里并被同类自调用，
 * {@code @Transactional} 被代理绕过：撤销 update 与留档 insert 失去原子性，
 * {@code afterCommit} 也退化为立即发 MQ（踩坑 #14 要防的"先发事件后回滚"保护名存实亡）。
 * 拆成独立 Bean 后事务由代理正常生效。调用方：定期复核、以及后续的管理端手工撤销。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyRevokeTxService {

    private final CompanyMapper companyMapper;
    private final CompanyVerifyRecordMapper recordMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 撤销认证：乐观更新 + 留档 + 同步索引 + 发「认证被撤销」事件。
     * <p>
     * 事件发送放在<b>事务提交后</b>（见踩坑记录 #14）：先发事件再回滚，
     * 会让 job 把一家其实还正常的企业岗位全部下线。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean revoke(Company company, String reason) {
        return revoke(company, reason, "PERIODIC_RECHECK", null);
    }

    /**
     * 撤销认证：乐观更新 + 留档 + 同步索引 + 发「认证被撤销」事件。
     * <p>
     * 事件发送放在<b>事务提交后</b>（见踩坑记录 #14）：先发事件再回滚，
     * 会让 job 把一家其实还正常的企业岗位全部下线。
     *
     * @param channel    留档渠道（PERIODIC_RECHECK 定期复核 / MANUAL 管理员手工，审计 F-16）
     * @param operatorId 操作人（定期复核为 null，管理员手工撤销为管理员用户 id）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean revoke(Company company, String reason, String channel, Long operatorId) {
        int rows = companyMapper.update(null, new LambdaUpdateWrapper<Company>()
                .eq(Company::getId, company.getId())
                .eq(Company::getVerifyStatus, 1)          // 只有仍处「已认证」才允许降到 3
                .set(Company::getVerifyStatus, 3)
                .set(Company::getVerifyRemark, reason)
                .set(Company::getVerifyTime, LocalDateTime.now()));
        if (rows == 0) {
            log.info("[企业复核] 企业 {} 状态已变化，跳过（并发复核或已被处理）", company.getId());
            return false;
        }

        CompanyVerifyRecord record = new CompanyVerifyRecord();
        record.setCompanyId(company.getId());
        record.setCreditCode(company.getCreditCode());
        record.setCompanyName(company.getName());
        record.setLegalPersonName(company.getLegalPersonName());
        record.setOperatorId(operatorId);
        record.setVerifyChannel(channel);
        record.setResult("撤销");
        record.setRemark(reason);
        recordMapper.insert(record);

        eventPublisher.publishEvent(new CompanySyncEvent(company.getId()));   // 认证状态变化 → 同步 company_index
        afterCommit(() -> publishRevokedEvent(company.getId(), reason));
        return true;
    }

    /** 发「认证被撤销」事件，让 job 批量下线该企业岗位 */
    private void publishRevokedEvent(Long companyId, String reason) {
        try {
            MqPayload.CompanyVerifyRevoked payload = new MqPayload.CompanyVerifyRevoked();
            payload.setCompanyId(companyId);
            payload.setReason(reason);

            MqMessage message = new MqMessage();
            message.setMessageId(UUID.randomUUID().toString().replace("-", ""));
            message.setBizType(MqConst.BizType.COMPANY_VERIFY_REVOKED);
            message.setBizId(companyId);
            message.setPayload(objectMapper.writeValueAsString(payload));

            rabbitTemplate.convertAndSend(MqConst.EXCHANGE, MqConst.RK_COMPANY_REVOKED, message);
            log.info("[企业复核] 已发出认证撤销事件: companyId={}", companyId);
        } catch (Exception e) {
            // 发失败不回滚撤销（认证状态本身已经不可信，撤销是对的）；
            // 岗位下线由 job 侧的兜底对账补偿
            log.error("[企业复核] 发出认证撤销事件失败（job 侧对账会补偿）: companyId={} err={}",
                    companyId, e.getMessage());
        }
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
