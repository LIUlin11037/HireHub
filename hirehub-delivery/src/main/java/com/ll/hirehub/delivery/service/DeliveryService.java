package com.ll.hirehub.delivery.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ll.hirehub.api.CompanyClient;
import com.ll.hirehub.api.JobClient;
import com.ll.hirehub.api.ResumeClient;
import com.ll.hirehub.api.dto.CompanyMemberDTO;
import com.ll.hirehub.api.dto.JobDTO;
import com.ll.hirehub.api.dto.ResumeDTO;
import com.ll.hirehub.common.exception.BusinessException;
import com.ll.hirehub.common.result.ResultCode;
import com.ll.hirehub.delivery.dto.ApplyRequest;
import com.ll.hirehub.delivery.entity.Delivery;
import com.ll.hirehub.delivery.entity.DeliveryResume;
import com.ll.hirehub.delivery.enums.DeliveryEvent;
import com.ll.hirehub.delivery.enums.DeliveryStatus;
import com.ll.hirehub.delivery.mapper.DeliveryMapper;
import com.ll.hirehub.delivery.mapper.DeliveryResumeMapper;
import com.ll.hirehub.delivery.statemachine.DeliveryStateMachine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

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

    /** 投递：幂等（预检 + DB 唯一索引兜底）+ 简历快照（见 D-26） */
    @Transactional(rollbackFor = Exception.class)
    public Long apply(Long seekerId, ApplyRequest req) {
        // 幂等预检
        Delivery exist = deliveryMapper.selectOne(new LambdaQueryWrapper<Delivery>()
                .eq(Delivery::getJobId, req.getJobId())
                .eq(Delivery::getResumeId, req.getResumeId()));
        if (exist != null) {
            return exist.getId();
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
        deliveryMapper.insert(delivery); // 并发重复投递由 uk(job_id, resume_id) 保证不重复

        DeliveryResume dr = new DeliveryResume();
        dr.setDeliveryId(delivery.getId());
        dr.setResumeId(req.getResumeId());
        dr.setResumeSnapshot(toJson(resume));
        dr.setSnapshotTime(LocalDateTime.now());
        resumeMapper.insert(dr);
        return delivery.getId();
    }

    /** 查看详情：HR 打开时自动 PENDING → VIEWED（见 §6.2 事件边界） */
    @Transactional(rollbackFor = Exception.class)
    public Delivery get(Long userId, Long deliveryId) {
        Delivery d = requireDelivery(deliveryId);
        String actor = determineActor(userId, d);
        if ("HR".equals(actor) && DeliveryStatus.PENDING.name().equals(d.getStatus())) {
            d.setStatus(DeliveryStatus.VIEWED.name());
            deliveryMapper.updateById(d);
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

    /** 发起事件：状态机决定能否迁移 + 乐观锁防并发（见 D-27） */
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
    }

    public List<Delivery> mine(Long seekerId) {
        return deliveryMapper.selectList(new LambdaQueryWrapper<Delivery>()
                .eq(Delivery::getSeekerId, seekerId)
                .orderByDesc(Delivery::getCreateTime));
    }

    public List<Delivery> received(Long companyId, Long userId) {
        CompanyMemberDTO member = companyClient.getMember(companyId, userId).getData();
        if (member == null || member.getStatus() == null || member.getStatus() != 1) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "非该企业在职成员");
        }
        return deliveryMapper.selectList(new LambdaQueryWrapper<Delivery>()
                .eq(Delivery::getCompanyId, companyId)
                .orderByDesc(Delivery::getCreateTime));
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
