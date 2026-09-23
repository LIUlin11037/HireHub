package com.ll.hirehub.interview.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ll.hirehub.common.mq.MqConst;
import com.ll.hirehub.common.mq.MqMessage;
import com.ll.hirehub.common.mq.MqPayload;
import com.ll.hirehub.interview.entity.Interview;
import com.ll.hirehub.interview.entity.InterviewReminder;
import com.ll.hirehub.interview.enums.InterviewStatus;
import com.ll.hirehub.interview.mapper.InterviewMapper;
import com.ll.hirehub.interview.mapper.InterviewReminderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 面试提醒排布与触发（见 D-32）。
 * <p>
 * 三条正确性保障，缺一条就会「漏提醒」或「重复提醒」：
 * <ol>
 *   <li><b>幂等占位</b>：只有把状态从 0 改成 1 的调用方才发通知（乐观更新看受影响行数）。
 *       延迟消息与兜底扫描可能同时到达，这样天然只发一次。</li>
 *   <li><b>令牌校验</b>：改期会重生 {@code token}，旧延迟消息到期时令牌不匹配 → 丢弃。
 *       否则面试改到明天之后，今天那条"1 天后提醒"还会按老时间发出去。</li>
 *   <li><b>状态与时间双校验</b>：面试已取消 / 已完成 / 已开始，都不再提醒。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewReminderService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 提前量档位（顺序即排布顺序）。1 天 = 86400000ms，远低于 RabbitMQ 消息 TTL 上限 2^31-1 */
    private static final Map<String, Duration> TIERS = new LinkedHashMap<>();

    static {
        TIERS.put(InterviewReminder.TIER_1D, Duration.ofDays(1));
        TIERS.put(InterviewReminder.TIER_30M, Duration.ofMinutes(30));
    }

    private static final Map<String, String> TIER_TEXT = Map.of(
            InterviewReminder.TIER_1D, "1 天",
            InterviewReminder.TIER_30M, "30 分钟");

    private final InterviewReminderMapper reminderMapper;
    private final InterviewMapper interviewMapper;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------ 排布

    /**
     * 排布一场面试的提醒（创建与改期都调这里）。
     * <p>
     * 改期场景依赖 {@code uk(interview_id, tier)} 做"更新而非新增"：
     * 同档位提醒永远只有一行，重置 {@code remind_time} + 换 {@code token} 即完成取代。
     * 调用方在 {@code @Transactional} 里，本方法只写库 + 投消息；
     * 即使事务回滚，延迟消息到期时也查不到提醒行 → 自然丢弃，不会发幽灵提醒。
     */
    public void schedule(Interview interview) {
        if (interview == null || interview.getId() == null || interview.getInterviewTime() == null) {
            log.warn("面试时间缺失，跳过提醒排布: interviewId={}",
                    interview == null ? null : interview.getId());
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        String token = UUID.randomUUID().toString().replace("-", "");
        for (Map.Entry<String, Duration> entry : TIERS.entrySet()) {
            String tier = entry.getKey();
            LocalDateTime remindAt = interview.getInterviewTime().minus(entry.getValue());

            InterviewReminder reminder = upsert(interview.getId(), tier, remindAt, token, now);
            if (reminder == null) {
                continue;
            }

            long delayMs = Duration.between(now, remindAt).toMillis();
            if (delayMs <= 0) {
                // 提前量已过（例如临时约 2 小时后的面试）：立即提醒，别排一条永远等不到的延迟消息
                log.info("提醒时间已到点，立即发送: interviewId={} tier={}", interview.getId(), tier);
                fire(reminder, interview);
            } else {
                publishDelayMessage(reminder, delayMs);
            }
        }
    }

    /** 作废该面试所有未发出的提醒（取消 / 拒绝 / 已完成） */
    public void cancel(Long interviewId) {
        if (interviewId == null) {
            return;
        }
        int rows = reminderMapper.update(null, new LambdaUpdateWrapper<InterviewReminder>()
                .eq(InterviewReminder::getInterviewId, interviewId)
                .eq(InterviewReminder::getStatus, InterviewReminder.STATUS_PENDING)
                .set(InterviewReminder::getStatus, InterviewReminder.STATUS_CANCELLED)
                .set(InterviewReminder::getUpdateTime, LocalDateTime.now()));
        if (rows > 0) {
            log.info("作废面试 {} 的 {} 条待发提醒", interviewId, rows);
        }
    }

    // ------------------------------------------------------------ 触发

    /**
     * 延迟消息到期（DLX 投递）后的处理入口。
     * 校验顺序：取出提醒行 → 令牌/状态 → 面试是否仍然有效。
     */
    public void onDelayMessage(MqMessage message) throws Exception {
        MqPayload.InterviewRemind payload =
                objectMapper.readValue(message.getPayload(), MqPayload.InterviewRemind.class);
        InterviewReminder reminder = find(payload.getInterviewId(), payload.getTier());
        if (reminder == null) {
            log.info("提醒记录不存在（面试已删除），忽略: interviewId={} tier={}",
                    payload.getInterviewId(), payload.getTier());
            return;
        }
        if (reminder.getStatus() == null || reminder.getStatus() != InterviewReminder.STATUS_PENDING) {
            log.info("提醒已处理或已作废，忽略: interviewId={} tier={} status={}",
                    payload.getInterviewId(), payload.getTier(), reminder.getStatus());
            return;
        }
        if (!Objects.equals(payload.getToken(), reminder.getToken())) {
            log.info("提醒令牌已过期（面试改期后被取代），忽略: interviewId={} tier={}",
                    payload.getInterviewId(), payload.getTier());
            return;
        }

        Interview interview = interviewMapper.selectById(reminder.getInterviewId());
        if (!stillValid(interview)) {
            discard(reminder);
            return;
        }
        fire(reminder, interview);
    }

    /** 面试是否仍然值得提醒：未取消 / 未完成 / 尚未开始 */
    public boolean stillValid(Interview interview) {
        if (interview == null || interview.getInterviewTime() == null) {
            return false;
        }
        if (!InterviewStatus.SCHEDULED.name().equals(interview.getStatus())
                && !InterviewStatus.CONFIRMED.name().equals(interview.getStatus())) {
            return false;
        }
        return interview.getInterviewTime().isAfter(LocalDateTime.now());
    }

    /**
     * 真正发送一条提醒。
     *
     * @return true 表示本次抢到了发送权（用于日志与测试断言）
     */
    public boolean fire(InterviewReminder reminder, Interview interview) {
        // 乐观占位：0 → 1，受影响行数为 1 才算抢到。延迟消息与兜底扫描撞车时只发一次。
        int claimed = reminderMapper.update(null, new LambdaUpdateWrapper<InterviewReminder>()
                .eq(InterviewReminder::getId, reminder.getId())
                .eq(InterviewReminder::getStatus, InterviewReminder.STATUS_PENDING)
                .set(InterviewReminder::getStatus, InterviewReminder.STATUS_SENT)
                .set(InterviewReminder::getUpdateTime, LocalDateTime.now()));
        if (claimed == 0) {
            return false;
        }

        try {
            rabbitTemplate.convertAndSend(MqConst.EXCHANGE, MqConst.RK_INTERVIEW_REMIND_NOTIFY,
                    buildNotifyMessage(interview, reminder.getTier()));
            log.info("面试提醒已发出: interviewId={} tier={} seekerId={}",
                    interview.getId(), reminder.getTier(), interview.getSeekerId());
            return true;
        } catch (Exception e) {
            // 占位成功但通知没发出去：回退成待发，交给兜底扫描重试，避免这条提醒彻底丢掉
            log.error("面试提醒投递失败，回退为待发（兜底扫描会重试）: interviewId={} tier={} err={}",
                    interview.getId(), reminder.getTier(), e.getMessage());
            reminderMapper.update(null, new LambdaUpdateWrapper<InterviewReminder>()
                    .eq(InterviewReminder::getId, reminder.getId())
                    .set(InterviewReminder::getStatus, InterviewReminder.STATUS_PENDING)
                    .set(InterviewReminder::getUpdateTime, LocalDateTime.now()));
            return false;
        }
    }

    /** 标记作废（面试已失效） */
    public void discard(InterviewReminder reminder) {
        reminderMapper.update(null, new LambdaUpdateWrapper<InterviewReminder>()
                .eq(InterviewReminder::getId, reminder.getId())
                .eq(InterviewReminder::getStatus, InterviewReminder.STATUS_PENDING)
                .set(InterviewReminder::getStatus, InterviewReminder.STATUS_CANCELLED)
                .set(InterviewReminder::getUpdateTime, LocalDateTime.now()));
    }

    public List<InterviewReminder> dueReminders(LocalDateTime now, int limit) {
        return reminderMapper.selectList(new LambdaQueryWrapper<InterviewReminder>()
                .eq(InterviewReminder::getStatus, InterviewReminder.STATUS_PENDING)
                .le(InterviewReminder::getRemindTime, now)
                .orderByAsc(InterviewReminder::getRemindTime)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 500)));
    }

    // ------------------------------------------------------------ 内部

    private InterviewReminder find(Long interviewId, String tier) {
        if (interviewId == null || tier == null) {
            return null;
        }
        return reminderMapper.selectOne(new LambdaQueryWrapper<InterviewReminder>()
                .eq(InterviewReminder::getInterviewId, interviewId)
                .eq(InterviewReminder::getTier, tier));
    }

    /** 存在则重置（改期取代），不存在则新建——靠唯一键保证同档位只有一行 */
    private InterviewReminder upsert(Long interviewId, String tier, LocalDateTime remindAt,
                                    String token, LocalDateTime now) {
        InterviewReminder existing = find(interviewId, tier);
        if (existing == null) {
            InterviewReminder fresh = new InterviewReminder();
            fresh.setInterviewId(interviewId);
            fresh.setTier(tier);
            fresh.setToken(token);
            fresh.setRemindTime(remindAt);
            fresh.setStatus(InterviewReminder.STATUS_PENDING);
            fresh.setCreateTime(now);
            fresh.setUpdateTime(now);
            reminderMapper.insert(fresh);
            return fresh;
        }
        // 无条件重置为待发：上一轮可能已经发过（改期后要再提醒一次）或已作废
        reminderMapper.update(null, new LambdaUpdateWrapper<InterviewReminder>()
                .eq(InterviewReminder::getId, existing.getId())
                .set(InterviewReminder::getToken, token)
                .set(InterviewReminder::getRemindTime, remindAt)
                .set(InterviewReminder::getStatus, InterviewReminder.STATUS_PENDING)
                .set(InterviewReminder::getUpdateTime, now));
        existing.setToken(token);
        existing.setRemindTime(remindAt);
        existing.setStatus(InterviewReminder.STATUS_PENDING);
        return existing;
    }

    /** 投递延迟消息：走默认 exchange，routing key = 队列名，per-message TTL 决定延迟时长 */
    private void publishDelayMessage(InterviewReminder reminder, long delayMs) {
        try {
            MqPayload.InterviewRemind payload = new MqPayload.InterviewRemind();
            payload.setInterviewId(reminder.getInterviewId());
            payload.setTier(reminder.getTier());
            payload.setToken(reminder.getToken());

            MqMessage message = new MqMessage();
            message.setMessageId(UUID.randomUUID().toString().replace("-", ""));
            message.setBizType(MqConst.BizType.INTERVIEW_REMIND);
            message.setBizId(reminder.getInterviewId());
            message.setPayload(objectMapper.writeValueAsString(payload));

            rabbitTemplate.convertAndSend("", MqConst.QUEUE_INTERVIEW_REMIND_DELAY, message, msg -> {
                msg.getMessageProperties().setExpiration(String.valueOf(delayMs));
                return msg;
            });
            log.info("已排布面试提醒延迟消息: interviewId={} tier={} delayMs={}",
                    reminder.getInterviewId(), reminder.getTier(), delayMs);
        } catch (Exception e) {
            // 投递失败不影响面试创建：兜底扫描会按 remind_time 补发
            log.error("排布面试提醒延迟消息失败（兜底扫描会补发）: interviewId={} tier={} err={}",
                    reminder.getInterviewId(), reminder.getTier(), e.getMessage());
        }
    }

    private MqMessage buildNotifyMessage(Interview interview, String tier) throws Exception {
        MqPayload.InterviewRemind payload = new MqPayload.InterviewRemind();
        payload.setInterviewId(interview.getId());
        payload.setSeekerId(interview.getSeekerId());
        payload.setTier(tier);
        payload.setContent(String.format("您有一场面试将在%s后开始：%s。面试编号 %d，请准时参加。",
                TIER_TEXT.getOrDefault(tier, tier),
                interview.getInterviewTime().format(TIME_FMT),
                interview.getId()));
        // 面试提醒只发给求职者：HR 是面试的组织方，自己知道时间
        payload.setReceiverIds(List.of(interview.getSeekerId()));

        MqMessage message = new MqMessage();
        message.setMessageId(UUID.randomUUID().toString().replace("-", ""));
        message.setBizType(MqConst.BizType.INTERVIEW_REMIND);
        message.setBizId(interview.getId());
        message.setPayload(objectMapper.writeValueAsString(payload));
        return message;
    }
}
