package com.ll.hirehub.job.service;

import com.ll.hirehub.common.exception.BusinessException;
import com.ll.hirehub.common.result.ResultCode;
import com.ll.hirehub.job.entity.Job;
import com.ll.hirehub.job.mapper.JobMapper;
import com.ll.hirehub.job.search.JobSyncEvent;
import com.ll.hirehub.job.vo.JobStatisticsVO;
import com.ll.hirehub.job.cache.JobCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 管理端职位操作（见 D-07）。
 * <p>
 * 与 {@link JobService#offline} 的区别：那是<b>企业成员自助下线</b>（要校验归属与角色），
 * 这里是<b>平台管理员强制下架违规职位</b>（不校验企业归属，操作人来自网关注入的管理员身份）。
 * 两者共用同一个状态字段，用 {@code offline_reason} 区分责任方——这正是"可追溯"的落点。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobAdminService {

    private final JobMapper jobMapper;
    private final JobCacheService jobCacheService;
    private final ApplicationEventPublisher eventPublisher;

    /** 管理员强制下架：只处理对外可见的职位（草稿无需下架） */
    @Transactional(rollbackFor = Exception.class)
    public void offline(Long jobId, String reason) {
        Job job = jobMapper.selectById(jobId);
        if (job == null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        if (job.getStatus() == null || job.getStatus() == 0) {
            throw new BusinessException("草稿职位未对外展示，无需下架");
        }
        if (job.getStatus() == 2) {
            throw new BusinessException("该职位已下线");
        }
        job.setStatus(2);
        job.setOfflineReason(reason == null || reason.isBlank() ? "管理员下架" : reason);
        jobMapper.updateById(job);
        jobCacheService.evict(jobId);
        eventPublisher.publishEvent(new JobSyncEvent(jobId, false));   // status=2 会被搜索过滤掉
    }

    public JobStatisticsVO statistics() {
        JobStatisticsVO vo = new JobStatisticsVO();

        Map<String, Object> totals = jobMapper.totals();
        vo.setTotal(asLong(totals, "cnt"));
        vo.setTotalDeliveryCount(asLong(totals, "deliveries"));
        vo.setTotalViewCount(asLong(totals, "views"));

        for (Map<String, Object> row : jobMapper.countByStatus()) {
            long cnt = asLong(row, "cnt");
            long status = asLong(row, "label");
            switch ((int) status) {
                case 0 -> vo.setDraft(cnt);
                case 1 -> vo.setOnline(cnt);
                case 2 -> vo.setOffline(cnt);
                case 3 -> vo.setFull(cnt);
                default -> log.warn("未知职位状态: {}", status);
            }
        }

        vo.setTopCities(toItems(jobMapper.topCities()));
        vo.setTopCategories(toItems(jobMapper.topCategories()));
        return vo;
    }

    private List<JobStatisticsVO.CountItem> toItems(List<Map<String, Object>> rows) {
        return rows.stream()
                .map(row -> {
                    Object label = row.get("label");
                    return new JobStatisticsVO.CountItem(label == null ? "未分类" : String.valueOf(label),
                            asLong(row, "cnt"));
                })
                .toList();
    }

    /** 不同驱动可能返回 Long / BigInteger / Integer，统一收敛，避免 ClassCastException */
    private long asLong(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
