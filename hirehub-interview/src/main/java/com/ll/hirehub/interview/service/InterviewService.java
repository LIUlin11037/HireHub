package com.ll.hirehub.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ll.hirehub.api.CompanyClient;
import com.ll.hirehub.api.DeliveryClient;
import com.ll.hirehub.api.dto.CompanyMemberDTO;
import com.ll.hirehub.api.dto.DeliveryDTO;
import com.ll.hirehub.common.exception.BusinessException;
import com.ll.hirehub.common.mq.MqConst;
import com.ll.hirehub.common.mq.MqPayload;
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
import com.ll.hirehub.interview.mq.InterviewReminderService;
import com.ll.hirehub.interview.mq.LocalMessageRecorder;
import com.ll.hirehub.interview.statemachine.InterviewStateMachine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
public class InterviewService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 审计 F-04：允许发起面试的投递状态（与投递状态机 D-27 对齐，不反向依赖 delivery 模块） */
    private static final java.util.Set<String> ALLOWED_DELIVERY_STATUS =
            java.util.Set.of("PENDING", "VIEWED", "COMMUNICATING", "INTERVIEW");

    private final InterviewMapper interviewMapper;
    private final InterviewFeedbackMapper feedbackMapper;
    private final InterviewStateMachine stateMachine;
    private final DeliveryClient deliveryClient;
    private final CompanyClient companyClient;
    private final InterviewReminderService reminderService;
    private final LocalMessageRecorder localMessageRecorder;

    /** HR 发起面试邀约（见 §7 / D-28） */
    @Transactional(rollbackFor = Exception.class)
    public Long create(Long userId, CreateInterviewRequest req) {
        DeliveryDTO delivery = deliveryClient.getDelivery(req.getDeliveryId()).getData();
        if (delivery == null) {
            throw new BusinessException("投递不存在");
        }
        requireHr(userId, delivery.getCompanyId());
        // 审计 F-04：终态投递（REJECTED/CANCELLED/HIRED）不允许发起面试——
        // 否则投递状态机的白名单被旁路，两套状态机（D-28）失序且后端无兜底。
        // INTERVIEW 放行：同一投递的第 N 轮面试是合法场景。
        if (!ALLOWED_DELIVERY_STATUS.contains(delivery.getStatus())) {
            throw new BusinessException("当前投递状态（" + delivery.getStatus() + "）不允许发起面试");
        }

        Long count = interviewMapper.selectCount(new LambdaQueryWrapper<Interview>()
                .eq(Interview::getDeliveryId, req.getDeliveryId()));

        Interview iv = new Interview();
        iv.setDeliveryId(req.getDeliveryId());
        iv.setJobId(delivery.getJobId());
        iv.setCompanyId(delivery.getCompanyId());
        iv.setSeekerId(delivery.getSeekerId());
        iv.setInterviewTime(req.getInterviewTime());
        iv.setInterviewType(req.getInterviewType());
        iv.setAddressOrLink(req.getAddressOrLink());
        iv.setInterviewerName(req.getInterviewerName());
        iv.setStatus(InterviewStatus.SCHEDULED.name());
        // 审计 F-17③：round = count+1 在并发下会重号——uk(delivery_id, round) 兜底，
        // 冲突说明有并发创建插入了同一轮次，重算后重试一次（第二次仍冲突则真的是异常，抛出让事务回滚）
        try {
            iv.setRound(count.intValue() + 1);
            interviewMapper.insert(iv);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            Long fresh = interviewMapper.selectCount(new LambdaQueryWrapper<Interview>()
                    .eq(Interview::getDeliveryId, req.getDeliveryId()));
            iv.setId(null);
            iv.setRound(fresh.intValue() + 1);
            interviewMapper.insert(iv);
        }
        // 排布「面试前 1 天 / 30 分钟」两档提醒（见 D-32）。
        // 放在同一事务里：提醒行与面试一起提交，回滚则两样都没有，不会发出幽灵提醒。
        reminderService.schedule(iv);
        // 审计 R-02 / §6.9：同事务写本地消息，通知求职者（此前面试事件完全没有通知）
        recordInterviewEvent(iv, "CREATED", null, "收到面试邀请",
                String.format("您有一场新的面试安排：第 %d 轮，时间 %s，请及时确认。",
                        iv.getRound(), formatTime(iv.getInterviewTime())),
                List.of(iv.getSeekerId()));
        return iv.getId();
    }

    public List<Interview> mine(Long seekerId) {
        return interviewMapper.selectList(new LambdaQueryWrapper<Interview>()
                .eq(Interview::getSeekerId, seekerId)
                .orderByDesc(Interview::getCreateTime));
    }

    /**
     * HR 视角：本企业发出的全部面试（见 D-41）。
     * <p>
     * 与 {@link #mine} 正好相对：那个按 {@code seekerId} 查（求职者看"我的面试"），
     * 这个按 {@code companyId} 查（HR 看"我发出的面试"）。此前只有前者，
     * 于是 HR 侧根本列不出面试，前端只能靠 localStorage 记 id —— 换个设备就全丢。
     * <p>
     * 权限复用 {@link #requireHr}：非该企业在职 OWNER/HR → 403。
     */
    public List<Interview> company(Long companyId, Long userId) {
        requireHr(userId, companyId);
        return interviewMapper.selectList(new LambdaQueryWrapper<Interview>()
                .eq(Interview::getCompanyId, companyId)
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
        reminderService.cancel(interviewId);   // 求职者已拒绝，不再提醒
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long userId, Long interviewId, CancelRequest req) {
        Interview iv = requireInterview(interviewId);
        String actor = determineActor(userId, iv);
        // 审计 F-03：取消原因/取消方与状态迁移合进同一次条件更新，消除"迁移成功但字段没写上"的并发窗口
        transition(iv, InterviewEvent.CANCEL, actor,
                w -> w.set(Interview::getCancelledBy, actor)
                        .set(Interview::getCancelReason, req.getReason()));
        iv.setCancelledBy(actor);
        iv.setCancelReason(req.getReason());
        reminderService.cancel(interviewId);   // 取消失败也不该继续提醒
        // 审计 R-02 / §6.9：取消方是谁就通知另一方
        List<Long> receivers = "SEEKER".equals(actor)
                ? hrMemberIds(iv.getCompanyId())
                : List.of(iv.getSeekerId());
        String who = "SEEKER".equals(actor) ? "求职者" : "企业方";
        recordInterviewEvent(iv, "CANCELLED", actor, "面试取消通知",
                String.format("%s取消了原定于 %s 的面试（第 %d 轮）。原因：%s",
                        who, formatTime(iv.getInterviewTime()), iv.getRound(),
                        req.getReason() == null ? "未说明" : req.getReason()),
                receivers);
    }

    @Transactional(rollbackFor = Exception.class)
    public void reschedule(Long userId, Long interviewId, RescheduleRequest req) {
        Interview iv = requireInterview(interviewId);
        String actor = determineActor(userId, iv);
        transition(iv, InterviewEvent.RESCHEDULE, actor,
                w -> w.set(Interview::getInterviewTime, req.getInterviewTime()));
        iv.setInterviewTime(req.getInterviewTime());
        // 改期后重新排布：内部会换 token，令"改期前投出的旧延迟消息"到期即被丢弃
        reminderService.schedule(iv);
        // 审计 R-02 / §6.9：改期必须通知求职者（状态回到 SCHEDULED，需要重新确认）
        recordInterviewEvent(iv, "RESCHEDULED", null, "面试改期通知",
                String.format("您的面试（第 %d 轮）时间已调整为 %s，请重新确认。",
                        iv.getRound(), formatTime(req.getInterviewTime())),
                List.of(iv.getSeekerId()));
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
        reminderService.cancel(interviewId);   // 面试已完成，之前的提醒不再有意义
    }

    private void transition(Interview iv, InterviewEvent event, String actor) {
        transition(iv, event, actor, w -> {
        });
    }

    /**
     * 状态迁移 + 乐观锁（审计 F-03）：UPDATE ... WHERE status=前态，affected=0 即并发冲突。
     * 与投递侧 {@code applyEvent} 同一模式（§6.2 / D-27），此前用 updateById 全字段覆盖，
     * 两个并发迁移会互相覆盖（如"已取消的面试被置为 COMPLETED"）。
     */
    private void transition(Interview iv, InterviewEvent event, String actor,
                            Consumer<LambdaUpdateWrapper<Interview>> extraSets) {
        InterviewStatus current = InterviewStatus.valueOf(iv.getStatus());
        InterviewStatus target = stateMachine.apply(current, event, actor);
        LambdaUpdateWrapper<Interview> wrapper = new LambdaUpdateWrapper<Interview>()
                .eq(Interview::getId, iv.getId())
                .eq(Interview::getStatus, current.name())
                .set(Interview::getStatus, target.name());
        extraSets.accept(wrapper);
        int rows = interviewMapper.update(null, wrapper);
        if (rows == 0) {
            throw new BusinessException("状态已被他人修改，请刷新重试");
        }
        iv.setStatus(target.name());
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

    /**
     * 面试事件 → 本地消息表（审计 R-02 / §6.9，复用 D-01）。
     * 与业务变更同事务写入，后台 relay 可靠投递到 MQ，由 notification 落站内信。
     */
    private void recordInterviewEvent(Interview iv, String event, String cancelledBy,
                                      String title, String content, List<Long> receiverIds) {
        if (receiverIds == null || receiverIds.isEmpty()) {
            return;
        }
        MqPayload.InterviewEvent payload = new MqPayload.InterviewEvent();
        payload.setInterviewId(iv.getId());
        payload.setDeliveryId(iv.getDeliveryId());
        payload.setEvent(event);
        payload.setCancelledBy(cancelledBy);
        payload.setTitle(title);
        payload.setContent(content);
        payload.setReceiverIds(receiverIds);
        localMessageRecorder.record(MqConst.BizType.INTERVIEW_EVENT, iv.getId(),
                MqConst.RK_INTERVIEW_EVENT, payload);
    }

    private List<Long> hrMemberIds(Long companyId) {
        List<Long> memberIds = companyClient.listMemberUserIds(companyId).getData();
        return memberIds == null ? List.of() : memberIds;
    }

    private String formatTime(LocalDateTime time) {
        return time == null ? "待定" : time.format(TIME_FORMAT);
    }
}
