package com.ll.hirehub.delivery.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ll.hirehub.api.CompanyClient;
import com.ll.hirehub.api.JobClient;
import com.ll.hirehub.api.ResumeClient;
import com.ll.hirehub.api.dto.CompanyMemberDTO;
import com.ll.hirehub.api.dto.IdNameDTO;
import com.ll.hirehub.api.dto.JobDTO;
import com.ll.hirehub.api.dto.ResumeDTO;
import com.ll.hirehub.common.exception.BusinessException;
import com.ll.hirehub.common.mq.MqConst;
import com.ll.hirehub.common.mq.MqPayload;
import com.ll.hirehub.common.result.ResultCode;
import com.ll.hirehub.delivery.dto.ApplyRequest;
import com.ll.hirehub.delivery.entity.Delivery;
import com.ll.hirehub.delivery.entity.DeliveryResume;
import com.ll.hirehub.delivery.enums.DeliveryEvent;
import com.ll.hirehub.delivery.enums.DeliveryStatus;
import com.ll.hirehub.delivery.mapper.DeliveryMapper;
import com.ll.hirehub.delivery.mapper.DeliveryResumeMapper;
import com.ll.hirehub.delivery.mq.LocalMessageRecorder;
import com.ll.hirehub.delivery.statemachine.DeliveryStateMachine;
import com.ll.hirehub.delivery.vo.DeliveryVO;
import com.ll.hirehub.common.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 投递服务（见 D-26 / D-27 / D-28）。
 * <p>
 * 二期变化：投递创建与状态迁移都会在<b>同一个本地事务里</b>写一条 local_message，
 * 由后台任务可靠投递到 RabbitMQ，实现跨服务副作用（职位投递数 +1、站内通知）的最终一致性。
 */
@Service
@RequiredArgsConstructor
public class DeliveryService {

    private final DeliveryMapper deliveryMapper;
    private final DeliveryResumeMapper resumeMapper;
    private final DeliveryStateMachine stateMachine;
    private final JobClient jobClient;
    private final ResumeClient resumeClient;
    private final CompanyClient companyClient;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final LocalMessageRecorder localMessageRecorder;

    /** 投递：幂等（预检 + DB 唯一索引兜底）+ 简历快照（见 D-26）+ 本地消息（二期） */
    @Transactional(rollbackFor = Exception.class)
    public Long apply(Long seekerId, ApplyRequest req) {
        // 幂等预检（DB 是权威裁决）。
        // 审计 S-13：必须限定 seekerId——只按 (jobId, resumeId) 预检会把他人投递的 id 返回给调用方，
        // 形成"他人是否投递过某职位"的探测 oracle。
        Delivery exist = deliveryMapper.selectOne(new LambdaQueryWrapper<Delivery>()
                .eq(Delivery::getJobId, req.getJobId())
                .eq(Delivery::getResumeId, req.getResumeId())
                .eq(Delivery::getSeekerId, seekerId));
        if (exist != null) {
            // 防御性校验：即使查询条件被意外改动，也绝不返回他人投递的 id
            if (!Objects.equals(exist.getSeekerId(), seekerId)) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "投递参数有误");
            }
            return exist.getId();
        }
        // 快速并发拦截（§8.2）：SETNX 挡掉同一 (job,resume) 的并发重复投递。
        // 审计 Q-01：返回值必须用起来——占位失败说明有并发在途（或上次回滚的脏占位）：
        // 再查一次 DB，命中即复用（并发那单已提交），未命中继续走 DB uk 兜底（脏占位靠 1 天 TTL 自愈）。
        String idemKey = RedisKeys.DELIVERY_IDEM_PREFIX + req.getJobId() + ":" + req.getResumeId();
        Boolean firstOccupied = redisTemplate.opsForValue().setIfAbsent(idemKey, "1", Duration.ofDays(1));
        if (Boolean.FALSE.equals(firstOccupied)) {
            Delivery concurrent = deliveryMapper.selectOne(new LambdaQueryWrapper<Delivery>()
                    .eq(Delivery::getJobId, req.getJobId())
                    .eq(Delivery::getResumeId, req.getResumeId())
                    .eq(Delivery::getSeekerId, seekerId));
            if (concurrent != null) {
                return concurrent.getId();
            }
        }
        // 校验职位
        JobDTO job = jobClient.getJob(req.getJobId()).getData();
        if (job == null || job.getStatus() == null || job.getStatus() != 1) {
            throw new BusinessException("职位不存在或不在招聘中");
        }
        // 校验简历归属（不信任前端传的 resumeId，必须属于当前用户）
        ResumeDTO resume = resumeClient.getResume(req.getResumeId()).getData();
        if (resume == null || !resume.getUserId().equals(seekerId)) {
            throw new BusinessException("简历不存在或无权使用");
        }

        Delivery delivery = new Delivery();
        delivery.setJobId(req.getJobId());
        delivery.setResumeId(req.getResumeId());
        delivery.setSeekerId(seekerId);
        delivery.setCompanyId(job.getCompanyId());
        delivery.setStatus(DeliveryStatus.PENDING.name());
        try {
            deliveryMapper.insert(delivery);
        } catch (DuplicateKeyException e) {
            // 极端并发下 SETNX 与预检都没拦住，由 DB uk 兜底：查到已存在的那条并返回
            Delivery dup = deliveryMapper.selectOne(new LambdaQueryWrapper<Delivery>()
                    .eq(Delivery::getJobId, req.getJobId())
                    .eq(Delivery::getResumeId, req.getResumeId())
                    .eq(Delivery::getSeekerId, seekerId));
            return dup.getId();
        }

        DeliveryResume dr = new DeliveryResume();
        dr.setDeliveryId(delivery.getId());
        dr.setResumeId(req.getResumeId());
        dr.setResumeSnapshot(toJson(resume));
        dr.setSnapshotTime(LocalDateTime.now());
        resumeMapper.insert(dr);

        // ★ 同事务写本地消息：job 投递数 +1、通知 HR
        MqPayload.DeliveryCreated payload = new MqPayload.DeliveryCreated();
        payload.setDeliveryId(delivery.getId());
        payload.setJobId(delivery.getJobId());
        payload.setCompanyId(delivery.getCompanyId());
        payload.setSeekerId(seekerId);
        payload.setJobTitle(job.getTitle());
        payload.setSeekerName(resume.getName());
        payload.setReceiverIds(resolveReceivers("HR", delivery));
        localMessageRecorder.record(MqConst.BizType.DELIVERY_CREATED, delivery.getId(),
                MqConst.RK_DELIVERY_CREATED, payload);
        // 提交前把真实 deliveryId 写回，后续重复投递走 selectOne 直接命中
        redisTemplate.opsForValue().set(idemKey, String.valueOf(delivery.getId()), Duration.ofDays(1));
        return delivery.getId();
    }

    /** 查看详情：HR 打开时自动 PENDING → VIEWED（见 §6.2 事件边界） */
    @Transactional(rollbackFor = Exception.class)
    public Delivery get(Long userId, Long deliveryId) {
        Delivery d = requireDelivery(deliveryId);
        String actor = determineActor(userId, d);
        if ("HR".equals(actor) && DeliveryStatus.PENDING.name().equals(d.getStatus())) {
            // 审计 F-02：条件更新（WHERE status=PENDING），防止与求职者并发 CANCEL 互踩——
            // 全字段 updateById 会把已 CANCELLED 的投递"复活"回 VIEWED，逆迁终态（D-27）。
            // affected=0 说明状态已被并发修改，VIEW 幂等无需报错，以 DB 当前状态为准。
            int rows = deliveryMapper.update(null, new LambdaUpdateWrapper<Delivery>()
                    .eq(Delivery::getId, deliveryId)
                    .eq(Delivery::getStatus, DeliveryStatus.PENDING.name())
                    .set(Delivery::getStatus, DeliveryStatus.VIEWED.name()));
            if (rows > 0) {
                d.setStatus(DeliveryStatus.VIEWED.name());
            }
        }
        return d;
    }

    /** 投递简历快照（HR 看历史投递看投递那一刻的版本，见 D-26） */
    public DeliveryResume getSnapshot(Long userId, Long deliveryId) {
        Delivery d = requireDelivery(deliveryId);
        determineActor(userId, d);
        return resumeMapper.selectOne(new LambdaQueryWrapper<DeliveryResume>()
                .eq(DeliveryResume::getDeliveryId, deliveryId));
    }

    /**
     * 发起事件：状态机决定能否迁移 + 乐观锁防并发（见 D-27）
     * <p>
     * 迁移成功与「通知消息落库」在同一事务：状态改了但通知丢了是不可接受的。
     */
    @Transactional(rollbackFor = Exception.class)
    public void applyEvent(Long userId, Long deliveryId, DeliveryEvent event) {
        Delivery d = requireDelivery(deliveryId);
        String actor = determineActor(userId, d);
        DeliveryStatus current = DeliveryStatus.valueOf(d.getStatus());
        DeliveryStatus target = stateMachine.apply(current, event, actor);

        int rows = deliveryMapper.update(null, new LambdaUpdateWrapper<Delivery>()
                .eq(Delivery::getId, deliveryId)
                .eq(Delivery::getStatus, current.name())
                .set(Delivery::getStatus, target.name()));
        if (rows == 0) {
            throw new BusinessException("状态已被他人修改，请刷新重试");
        }

        // ★ 同事务写本地消息：状态变更通知（哪个事件通知谁见 §6.2 通知映射）
        String notifyTo = notifyTarget(event);
        if (notifyTo == null) {
            return;
        }
        List<Long> receiverIds = resolveReceivers(notifyTo, d);
        if (receiverIds.isEmpty()) {
            return;
        }
        JobDTO job = jobClient.getJob(d.getJobId()).getData();
        MqPayload.DeliveryEvent payload = new MqPayload.DeliveryEvent();
        payload.setDeliveryId(deliveryId);
        payload.setJobId(d.getJobId());
        payload.setCompanyId(d.getCompanyId());
        payload.setSeekerId(d.getSeekerId());
        payload.setJobTitle(job == null ? null : job.getTitle());
        payload.setEvent(event.name());
        payload.setFromStatus(current.name());
        payload.setToStatus(target.name());
        payload.setNotifyTo(notifyTo);
        payload.setReceiverIds(receiverIds);
        localMessageRecorder.record(MqConst.BizType.DELIVERY_EVENT, deliveryId,
                MqConst.RK_DELIVERY_EVENT, payload);
    }

    public List<DeliveryVO> mine(Long seekerId) {
        List<Delivery> list = deliveryMapper.selectList(new LambdaQueryWrapper<Delivery>()
                .eq(Delivery::getSeekerId, seekerId)
                .orderByDesc(Delivery::getCreateTime));
        return toVoList(list);
    }

    public List<DeliveryVO> received(Long companyId, Long userId) {
        CompanyMemberDTO member = companyClient.getMember(companyId, userId).getData();
        if (member == null || member.getStatus() == null || member.getStatus() != 1) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "非该企业在职成员");
        }
        List<Delivery> list = deliveryMapper.selectList(new LambdaQueryWrapper<Delivery>()
                .eq(Delivery::getCompanyId, companyId)
                .orderByDesc(Delivery::getCreateTime));
        return toVoList(list);
    }

    /** 详情同样带上展示名 —— 否则详情页也只能显示"职位ID 1" */
    public DeliveryVO getVo(Long userId, Long deliveryId) {
        return toVoList(List.of(get(userId, deliveryId))).get(0);
    }

    /**
     * 组装带展示名的投递视图（见 D-41）。
     * <p>
     * <b>为什么不是 N+1</b>：先把整页的 jobId / companyId / resumeId 各自<b>去重</b>，
     * 再对三个服务各发<b>一次</b>批量查询 —— 一页 N 条固定 <b>3 次</b>远程调用，与 N 无关。
     * 逐条查的话，一页 10 条就是 30 次。
     * <p>
     * 远端降级（Feign fallback 返回空集合）时，这里退化成"只有 ID、没有名字"，
     * 列表依然能正常渲染，不会因为查不到名字就把整个列表变成 500。
     */
    private List<DeliveryVO> toVoList(List<Delivery> list) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        Map<Long, String> jobTitles = toMap(
                jobClient.listTitles(distinct(list, Delivery::getJobId)).getData());
        Map<Long, String> companyNames = toMap(
                companyClient.listNames(distinct(list, Delivery::getCompanyId)).getData());
        // 注意：seekerName 是按 resumeId 批量查出来的（取简历里求职者自己填的展示名）
        Map<Long, String> resumeNames = toMap(
                resumeClient.listNames(distinct(list, Delivery::getResumeId)).getData());

        return list.stream().map(d -> {
            DeliveryVO vo = new DeliveryVO();
            vo.setId(d.getId());
            vo.setJobId(d.getJobId());
            vo.setJobTitle(jobTitles.get(d.getJobId()));
            vo.setResumeId(d.getResumeId());
            vo.setSeekerId(d.getSeekerId());
            vo.setSeekerName(resumeNames.get(d.getResumeId()));
            vo.setCompanyId(d.getCompanyId());
            vo.setCompanyName(companyNames.get(d.getCompanyId()));
            vo.setStatus(d.getStatus());
            vo.setCreateTime(d.getCreateTime());
            vo.setUpdateTime(d.getUpdateTime());
            return vo;
        }).toList();
    }

    private static List<Long> distinct(List<Delivery> list, Function<Delivery, Long> getter) {
        return list.stream().map(getter).filter(Objects::nonNull).distinct().toList();
    }

    private static Map<Long, String> toMap(List<IdNameDTO> list) {
        if (list == null || list.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> map = new HashMap<>();
        for (IdNameDTO dto : list) {
            if (dto != null && dto.getId() != null) {
                map.put(dto.getId(), dto.getName());
            }
        }
        return map;
    }

    /**
     * 通知映射（§6.2）：HR 发起的事件通知求职者，求职者的 CANCEL 通知 HR，VIEW 不通知。
     *
     * @return SEEKER / HR / null(不通知)
     */
    private String notifyTarget(DeliveryEvent event) {
        return switch (event) {
            case CONTACT, INVITE_INTERVIEW, SEND_OFFER, REJECT, HIRE -> "SEEKER";
            case CANCEL -> "HR";
            case VIEW -> null;
        };
    }

    /**
     * 解析接收人。通知 HR 时必须查企业成员表——这正是把接收人固化进消息负载的原因：
     * 否则 notification 服务也得依赖 company 服务。
     */
    private List<Long> resolveReceivers(String notifyTo, Delivery d) {
        if ("SEEKER".equals(notifyTo)) {
            return d.getSeekerId() == null ? Collections.emptyList() : List.of(d.getSeekerId());
        }
        List<Long> memberIds = companyClient.listMemberUserIds(d.getCompanyId()).getData();
        return memberIds == null ? Collections.emptyList() : memberIds;
    }

    /** 判断操作者身份：求职者本人 → SEEKER；企业成员 → HR */
    private String determineActor(Long userId, Delivery d) {
        if (userId.equals(d.getSeekerId())) {
            return "SEEKER";
        }
        CompanyMemberDTO member = companyClient.getMember(d.getCompanyId(), userId).getData();
        if (member != null && member.getStatus() != null && member.getStatus() == 1
                && ("OWNER".equals(member.getRole()) || "HR".equals(member.getRole()))) {
            return "HR";
        }
        throw new BusinessException(ResultCode.FORBIDDEN);
    }

    private Delivery requireDelivery(Long id) {
        Delivery d = deliveryMapper.selectById(id);
        if (d == null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        return d;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new BusinessException("快照序列化失败");
        }
    }
}
