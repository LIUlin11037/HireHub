package com.ll.hirehub.job.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ll.hirehub.api.CompanyClient;
import com.ll.hirehub.common.mq.MqConst;
import com.ll.hirehub.common.mq.MqMessage;
import com.ll.hirehub.common.mq.MqPayload;
import com.ll.hirehub.job.cache.JobCacheService;
import com.ll.hirehub.job.entity.Job;
import com.ll.hirehub.job.entity.MqConsumeLog;
import com.ll.hirehub.job.mapper.JobMapper;
import com.ll.hirehub.job.mapper.MqConsumeLogMapper;
import com.ll.hirehub.job.search.JobSyncEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 企业认证撤销 → 该企业在招职位批量下线（见 Q-08 状态联动）。
 * <p>
 * <b>为什么这件事必须做</b>：企业认证失效后它的岗位仍在对外展示，等于平台在用信用给一个
 * 不存在的招聘主体背书；求职者投进去也拿不到回应。所以"认证撤销"和"岗位下线"必须是同一个语义。
 * <p>
 * <b>两条路径</b>：
 * <ol>
 *   <li><b>主路径</b>：MQ 事件（company 发 {@code company.verify-revoked}，本服务消费）——实时、解耦。</li>
 *   <li><b>兜底对账</b>：{@link #reconcile()} 定时扫"有在招职位但企业认证已非通过"的企业补下线。
 *       存在的意义是**消息可能丢**（broker 重启、消费者当时不可用），而对账在最坏情况下也只是延迟收敛
 *       —— 这与 D-09 的"MQ 异步 + 定时对账"是同一种可靠性思路，不是重复建设。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyRevokeService {

    private static final String CONSUMER = MqConst.Consumer.JOB_COMPANY_REVOKE;
    private static final String DEFAULT_REASON = "企业认证失效";

    private final JobMapper jobMapper;
    private final MqConsumeLogMapper consumeLogMapper;
    private final CompanyClient companyClient;
    private final JobCacheService jobCacheService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /** @return true 表示本次真的处理了；false 表示重复消息 */
    @Transactional(rollbackFor = Exception.class)
    public boolean handle(MqMessage message) throws Exception {
        Long consumed = consumeLogMapper.selectCount(new LambdaQueryWrapper<MqConsumeLog>()
                .eq(MqConsumeLog::getMessageId, message.getMessageId())
                .eq(MqConsumeLog::getConsumer, CONSUMER));
        if (consumed != null && consumed > 0) {
            return false;
        }

        MqPayload.CompanyVerifyRevoked payload =
                objectMapper.readValue(message.getPayload(), MqPayload.CompanyVerifyRevoked.class);
        if (payload.getCompanyId() != null) {
            offlineByCompany(payload.getCompanyId(), payload.getReason());
        }

        MqConsumeLog logRow = new MqConsumeLog();
        logRow.setMessageId(message.getMessageId());
        logRow.setConsumer(CONSUMER);
        logRow.setCreateTime(LocalDateTime.now());
        consumeLogMapper.insert(logRow);   // 并发下由 uk(message_id, consumer) 兜底
        return true;
    }

    /**
     * 把某企业的在招职位全部下线。
     * <p>
     * 先查出 id 列表再按 id 批量更新，而不是直接 `update ... where company_id=?`：
     * 因为**每个职位都要清缓存 + 同步索引**，必须知道具体是哪些职位。
     * 只处理 {@code status=1}（招聘中）：草稿没对外展示、已下线的无需重复处理。
     *
     * @return 实际下线的职位数
     */
    @Transactional(rollbackFor = Exception.class)
    public int offlineByCompany(Long companyId, String reason) {
        List<Long> jobIds = jobMapper.selectList(new LambdaQueryWrapper<Job>()
                        .eq(Job::getCompanyId, companyId)
                        .eq(Job::getStatus, 1))
                .stream().map(Job::getId).toList();
        if (jobIds.isEmpty()) {
            return 0;
        }
        String offlineReason = (reason == null || reason.isBlank()) ? DEFAULT_REASON : reason;
        jobMapper.update(null, new LambdaUpdateWrapper<Job>()
                .in(Job::getId, jobIds)
                .set(Job::getStatus, 2)
                .set(Job::getOfflineReason, offlineReason));
        for (Long jobId : jobIds) {
            jobCacheService.evict(jobId);
            eventPublisher.publishEvent(new JobSyncEvent(jobId, false));   // status=2 会被搜索过滤掉
        }
        log.info("[认证联动] 企业 {} 认证失效，下线 {} 个在招职位（原因：{}）", companyId, jobIds.size(), offlineReason);
        return jobIds.size();
    }

    /**
     * 兜底对账：对"有在招职位、但企业认证已不是通过"的企业补下线。
     * <p>
     * 判据是**企业认证状态**（向 company 服务核实），不是"有没有收到事件"——
     * 这样无论认证是通过哪条路径失效的（定期复核、管理员手工撤销、将来新增的路径），
     * 这里都能收敛，不必每加一条路径就改一次。
     */
    @Scheduled(cron = "${hirehub.job.company-reconcile.cron:0 30 3 * * ?}")
    public int reconcile() {
        List<Long> companyIds = jobMapper.selectCompanyIdsWithOnlineJobs();
        if (companyIds.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Long companyId : companyIds) {
            try {
                Integer verifyStatus = companyClient.getVerifyStatus(companyId).getData();
                if (verifyStatus == null || verifyStatus != 1) {
                    total += offlineByCompany(companyId, "企业认证失效（对账补偿）");
                }
            } catch (Exception e) {
                // 查不到认证状态时不动：宁可晚一天收敛，也不能把正常企业的岗位误下线
                log.warn("[认证联动] 企业 {} 认证状态核实失败，本轮跳过: {}", companyId, e.getMessage());
            }
        }
        if (total > 0) {
            log.info("[认证联动] 对账补偿完成，共下线 {} 个职位", total);
        }
        return total;
    }
}
