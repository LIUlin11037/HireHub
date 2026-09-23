package com.ll.hirehub.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ll.hirehub.api.dto.OperationLogRequest;
import com.ll.hirehub.auth.entity.SysOperationLog;
import com.ll.hirehub.auth.entity.SysUser;
import com.ll.hirehub.auth.mapper.SysOperationLogMapper;
import com.ll.hirehub.auth.mapper.SysUserMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端操作审计（见 D-07）。
 * <p>
 * 审计回答的是"<b>管理员误操作怎么追溯</b>"：谁、在什么模块、对哪个对象、改成了什么、
 * 哪一次请求（traceId）。分散在各服务里的日志无法回答第一个问题，所以集中到 auth。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperationLogService {

    private static final int MAX_LIMIT = 200;

    private final SysOperationLogMapper logMapper;
    private final SysUserMapper userMapper;
    private final Tracer tracer;

    /** 落一条审计记录。调用方（company / job）在管理操作成功后上报。 */
    public void save(OperationLogRequest req) {
        SysOperationLog entity = new SysOperationLog();
        entity.setOperatorId(req.getOperatorId());
        // 操作人名称在本地补全：审计表与 sys_user 同库，不信任调用方传来的字符串
        entity.setOperatorName(resolveOperatorName(req));
        entity.setModule(req.getModule());
        entity.setAction(req.getAction());
        entity.setTargetType(req.getTargetType());
        entity.setTargetId(req.getTargetId());
        entity.setDetail(req.getDetail());
        entity.setIp(req.getIp());
        entity.setTraceId(req.getTraceId() != null ? req.getTraceId() : currentTraceId());
        entity.setCreateTime(LocalDateTime.now());
        logMapper.insert(entity);
    }

    private String resolveOperatorName(OperationLogRequest req) {
        if (req.getOperatorId() != null) {
            SysUser user = userMapper.selectById(req.getOperatorId());
            if (user != null) {
                return user.getNickname() != null ? user.getNickname() : user.getUsername();
            }
        }
        return req.getOperatorName();
    }

    /** 审计查询：按操作人 / 模块过滤，倒序取最近 N 条 */
    public List<SysOperationLog> query(Long operatorId, String module, Integer limit) {
        int size = limit == null ? 50 : Math.min(Math.max(limit, 1), MAX_LIMIT);
        return logMapper.selectList(new LambdaQueryWrapper<SysOperationLog>()
                .eq(operatorId != null, SysOperationLog::getOperatorId, operatorId)
                .eq(module != null && !module.isBlank(), SysOperationLog::getModule, module)
                .orderByDesc(SysOperationLog::getId)
                .last("LIMIT " + size));
    }

    private String currentTraceId() {
        Span span = tracer.currentSpan();
        return span == null ? null : span.context().traceId();
    }
}
