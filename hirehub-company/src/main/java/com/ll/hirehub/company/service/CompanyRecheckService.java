package com.ll.hirehub.company.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ll.hirehub.company.entity.Company;
import com.ll.hirehub.company.mapper.CompanyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 企业认证定期复核（见 Q-08 / D-18）。
 * <p>
 * <b>为什么需要它</b>：企业认证是"在某一个时刻"核验的，而企业会变 —— 注销、吊销之后，
 * 平台若继续把它标成「已认证」，就等于用平台的信用给一个不存在的实体背书，
 * 而且它的在招职位还在对外展示。所以要有"认证会过期"的机制。
 * <p>
 * 三条设计原则：
 * <ol>
 *   <li><b>只有"明确注销/吊销"才撤销</b>：工商接口超时、返回未知状态时<b>一律不动</b>。
 *       宁可漏一轮复核，也不能把正常企业误撤销 —— 误撤销会连带把它的岗位全部下线，
 *       代价远大于晚一天发现问题。</li>
 *   <li><b>乐观更新保证只生效一次</b>：`update ... where verify_status=1`，受影响行数为 1 才算抢到。
 *       多实例并发复核、以及"复核中管理员刚好手工撤销"都不会重复处理。</li>
 *   <li><b>状态联动通过 MQ 事件解耦</b>：company 不直接去改 job 的表（D-13 禁止跨库），
 *       只发「认证被撤销」事件；job 自己决定怎么处置自己的岗位。
 *       与公司对账任务一样，job 侧另有一道兜底对账（见 {@code CompanyRevokeService}），
 *       防止事件丢失导致岗位永远在线。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyRecheckService {

    /** 触发撤销的经营状态。吊销比注销更严重，一并撤销 */
    private static final Set<String> REVOKED_BUSINESS_STATUS = Set.of("注销", "吊销");

    private final CompanyMapper companyMapper;
    private final CompanyVerifier companyVerifier;
    /** 审计 F-05：撤销走独立事务 Bean，杜绝同类自调用导致 @Transactional 失效 */
    private final CompanyRevokeTxService revokeTxService;

    @Value("${hirehub.company.recheck.batch-size:50}")
    private int batchSize;

    /** 每天凌晨 3 点复核一批（cron 可配）。批大小有上限，避免一轮跑太久 */
    @Scheduled(cron = "${hirehub.company.recheck.cron:0 0 3 * * ?}")
    public void scheduledRecheck() {
        int revoked = recheckOnce();
        log.info("[企业复核] 定时复核完成，本轮撤销 {} 家", revoked);
    }

    /**
     * 跑一轮复核，返回本轮被撤销的企业数。
     * 定时任务与管理端手工触发（{@code POST /api/company/admin/recheck}）共用这一份逻辑。
     * <p>
     * 只挑「已认证」的企业，并按认证时间升序 —— 认证越久的越该先被复核。
     */
    public int recheckOnce() {
        int limit = Math.min(Math.max(batchSize, 1), 500);
        List<Company> batch = companyMapper.selectList(new LambdaQueryWrapper<Company>()
                .eq(Company::getVerifyStatus, 1)
                .orderByAsc(Company::getVerifyTime)
                .last("LIMIT " + limit));
        int revoked = 0;
        for (Company company : batch) {
            try {
                if (recheckOne(company)) {
                    revoked++;
                }
            } catch (Exception e) {
                // 单个企业复核失败不影响整轮；更不会因此撤销它（保守优先）
                log.warn("[企业复核] 企业 {} 复核异常，本轮跳过: {}", company.getId(), e.getMessage());
            }
        }
        log.info("[企业复核] 本轮检查 {} 家，撤销 {} 家", batch.size(), revoked);
        return revoked;
    }

    /**
     * 复核一家企业。
     *
     * @return true 表示本轮把它撤销了
     */
    public boolean recheckOne(Company company) {
        CompanyVerifyResult result = companyVerifier.verify(
                company.getName(), company.getCreditCode(), company.getLegalPersonName());
        String status = result.getBusinessStatus();
        if (status == null || !REVOKED_BUSINESS_STATUS.contains(status)) {
            return false;   // 存续 / 未知 / 查不到 → 不动
        }
        String reason = "定期复核：企业已" + status;
        log.info("[企业复核] 企业 {}（{}）经营状态={}，撤销认证", company.getId(), company.getName(), status);
        return revokeTxService.revoke(company, reason);
    }
}
