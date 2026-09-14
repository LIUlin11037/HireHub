package com.ll.hirehub.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ll.hirehub.api.CompanyClient;
import com.ll.hirehub.api.DeliveryClient;
import com.ll.hirehub.api.dto.CompanyMemberDTO;
import com.ll.hirehub.api.dto.DeliveryDTO;
import com.ll.hirehub.common.exception.BusinessException;
import com.ll.hirehub.common.result.ResultCode;
import com.ll.hirehub.interview.dto.CancelRequest;
import com.ll.hirehub.interview.dto.CreateInterviewRequest;
import com.ll.hirehub.interview.dto.FeedbackRequest;
import com.ll.hirehub.interview.dto.RescheduleRequest;
import com.ll.hirehub.interview.entity.Interview;
import com.ll.hirehub.interview.entity.InterviewFeedback;
import com.ll.hirehub.interview.enums.InterviewEvent;
import com.ll.hirehub.interview.enums.InterviewStatus;
import com.ll.hirehub.interview.mapper.InterviewFeedbackMapper;
import com.ll.hirehub.interview.mapper.InterviewMapper;
import com.ll.hirehub.interview.statemachine.InterviewStateMachine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class InterviewService {

    private final InterviewMapper interviewMapper;
    private final InterviewFeedbackMapper feedbackMapper;
    private final InterviewStateMachine stateMachine;
    private final DeliveryClient deliveryClient;
    private final CompanyClient companyClient;

    /** HR 发起面试邀约（见 §7 / D-28） */
    @Transactional(rollbackFor = Exception.class)
    public Long create(Long userId, CreateInterviewRequest req) {
        DeliveryDTO delivery = deliveryClient.getDelivery(req.getDeliveryId()).getData();
        if (delivery == null) {
            throw new BusinessException("投递不存在");
        }
        requireHr(userId, delivery.getCompanyId());

        Long count = interviewMapper.selectCount(new LambdaQueryWrapper<Interview>()
                .eq(Interview::getDeliveryId, req.getDeliveryId()));

        Interview iv = new Interview();
        iv.setDeliveryId(req.getDeliveryId());
        iv.setJobId(delivery.getJobId());
        iv.setCompanyId(delivery.getCompanyId());
        iv.setSeekerId(delivery.getSeekerId());
        iv.setRound(count.intValue() + 1);
        iv.setInterviewTime(req.getInterviewTime());
        iv.setInterviewType(req.getInterviewType());
        iv.setAddressOrLink(req.getAddressOrLink());
        iv.setInterviewerName(req.getInterviewerName());
        iv.setStatus(InterviewStatus.SCHEDULED.name());
        interviewMapper.insert(iv);
        return iv.getId();
    }

    public List<Interview> mine(Long seekerId) {
        return interviewMapper.selectList(new LambdaQueryWrapper<Interview>()
                .eq(Interview::getSeekerId, seekerId)
                .orderByDesc(Interview::getCreateTime));
    }

    public Interview get(Long userId, Long interviewId) {
        Interview iv = requireInterview(interviewId);
        determineActor(userId, iv);
        return iv;
    }

    @Transactional(rollbackFor = Exception.class)
    public void confirm(Long userId, Long interviewId) {
        Interview iv = requireInterview(interviewId);
        transition(iv, InterviewEvent.CONFIRM, determineActor(userId, iv));
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long userId, Long interviewId) {
        Interview iv = requireInterview(interviewId);
        transition(iv, InterviewEvent.REJECT, determineActor(userId, iv));
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long userId, Long interviewId, CancelRequest req) {
        Interview iv = requireInterview(interviewId);
        String actor = determineActor(userId, iv);
        transition(iv, InterviewEvent.CANCEL, actor);
        iv.setCancelledBy(actor);
        iv.setCancelReason(req.getReason());
        interviewMapper.updateById(iv);
    }

    @Transactional(rollbackFor = Exception.class)
    public void reschedule(Long userId, Long interviewId, RescheduleRequest req) {
        Interview iv = requireInterview(interviewId);
        String actor = determineActor(userId, iv);
        transition(iv, InterviewEvent.RESCHEDULE, actor);
        iv.setInterviewTime(req.getInterviewTime());
        interviewMapper.updateById(iv);
    }

    @Transactional(rollbackFor = Exception.class)
    public void feedback(Long userId, Long interviewId, FeedbackRequest req) {
        Interview iv = requireInterview(interviewId);
        transition(iv, InterviewEvent.COMPLETE, determineActor(userId, iv));
        InterviewFeedback fb = new InterviewFeedback();
        fb.setInterviewId(interviewId);
        fb.setRecorderId(userId);
        fb.setResult(req.getResult());
        fb.setComment(req.getComment());
        feedbackMapper.insert(fb);
    }

    private void transition(Interview iv, InterviewEvent event, String actor) {
        InterviewStatus current = InterviewStatus.valueOf(iv.getStatus());
        InterviewStatus target = stateMachine.apply(current, event, actor);
        iv.setStatus(target.name());
        interviewMapper.updateById(iv);
    }

    private String determineActor(Long userId, Interview iv) {
        if (userId.equals(iv.getSeekerId())) {
            return "SEEKER";
        }
        CompanyMemberDTO member = companyClient.getMember(iv.getCompanyId(), userId).getData();
        if (member != null && member.getStatus() != null && member.getStatus() == 1
                && ("OWNER".equals(member.getRole()) || "HR".equals(member.getRole()))) {
            return "HR";
        }
        throw new BusinessException(ResultCode.FORBIDDEN);
    }

    private void requireHr(Long userId, Long companyId) {
        CompanyMemberDTO member = companyClient.getMember(companyId, userId).getData();
        if (member == null || member.getStatus() == null || member.getStatus() != 1
                || !("OWNER".equals(member.getRole()) || "HR".equals(member.getRole()))) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "非该企业在职成员");
        }
    }

    private Interview requireInterview(Long id) {
        Interview iv = interviewMapper.selectById(id);
        if (iv == null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        return iv;
    }
}
