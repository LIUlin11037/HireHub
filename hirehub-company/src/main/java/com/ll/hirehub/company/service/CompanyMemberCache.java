package com.ll.hirehub.company.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ll.hirehub.api.dto.CompanyMemberDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 成员关系缓存（见 D-06）。
 * <p>
 * <b>为什么缓存放在 company（数据归属方）而不是各调用方</b>：
 * 归属校验的写路径（加入企业 / 退出 / 改角色）全部在 company，只有这里能保证
 * "写入即失效"。若让 job / delivery 各自缓存，就会出现"company 已改、某个调用方还在用旧值"
 * 的权限窗口——而权限缓存不一致是水平越权的直接来源。
 * <p>
 * 三条纪律：
 * <ol>
 *   <li><b>缓存只是加速，DB 永远是权威</b>：读失败 / 反序列化失败一律回落 DB，不因缓存故障阻断业务。</li>
 *   <li><b>否定结果也缓存</b>（防穿透），但 TTL 更短——TTL 内新加入的成员由写路径主动失效兜住。</li>
 *   <li><b>写路径必须 evict</b>，不能只依赖 TTL 过期。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyMemberCache {

    private static final String KEY_PREFIX = "member:";
    private static final Duration TTL_HIT = Duration.ofMinutes(30);
    private static final Duration TTL_MISS = Duration.ofMinutes(5);
    /** 否定结果的哨兵值：区分"确实不是成员"与"缓存里没有这个 key" */
    private static final String NULL_SENTINEL = "__NULL__";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /** 读缓存。返回 null 表示未命中或缓存不可用，调用方应回落 DB。 */
    public CompanyMemberDTO get(Long companyId, Long userId) {
        try {
            String raw = redis.opsForValue().get(key(companyId, userId));
            if (raw == null) {
                return null;
            }
            if (NULL_SENTINEL.equals(raw)) {
                // 命中否定缓存：确实不是成员。用一个 status=0 的 DTO 表达"已确认不存在"，
                // 与"未命中（返回 null）"区分开。
                CompanyMemberDTO absent = new CompanyMemberDTO();
                absent.setCompanyId(companyId);
                absent.setUserId(userId);
                absent.setStatus(0);
                return absent;
            }
            return objectMapper.readValue(raw, CompanyMemberDTO.class);
        } catch (Exception e) {
            log.warn("成员缓存读取失败，回落 DB: companyId={} userId={} err={}", companyId, userId, e.getMessage());
            return null;
        }
    }

    public void put(CompanyMemberDTO dto) {
        if (dto == null || dto.getCompanyId() == null || dto.getUserId() == null) {
            return;
        }
        try {
            boolean absent = dto.getStatus() == null || dto.getStatus() != 1;
            if (absent && dto.getRole() == null) {
                redis.opsForValue().set(key(dto.getCompanyId(), dto.getUserId()), NULL_SENTINEL, TTL_MISS);
            } else {
                redis.opsForValue().set(key(dto.getCompanyId(), dto.getUserId()),
                        objectMapper.writeValueAsString(dto), TTL_HIT);
            }
        } catch (Exception e) {
            log.warn("成员缓存写入失败（忽略，不影响业务）: {}", e.getMessage());
        }
    }

    /** 成员关系变化（加入 / 退出 / 改角色 / 状态变更）后必须调用 */
    public void evict(Long companyId, Long userId) {
        try {
            redis.delete(key(companyId, userId));
        } catch (Exception e) {
            log.warn("成员缓存失效失败: companyId={} userId={} err={}", companyId, userId, e.getMessage());
        }
    }

    private String key(Long companyId, Long userId) {
        return KEY_PREFIX + companyId + ":" + userId;
    }
}
