package com.ll.hirehub.interview.schedule;

import com.ll.hirehub.interview.entity.Interview;
import com.ll.hirehub.interview.entity.InterviewReminder;
import com.ll.hirehub.interview.mapper.InterviewMapper;
import com.ll.hirehub.interview.mq.InterviewReminderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试提醒兜底扫描（见 D-32）。
 * <p>
 * <b>为什么必须有它</b>——延迟消息方案有两个绕不开的窟窿：
 * <ol>
 *   <li><b>队头阻塞</b>：TTL 是消息级参数，RabbitMQ 只在队头消息到期时才检查它。
 *       一条"1 天后提醒"排在队头，后面那条"30 分钟后提醒"就得干等一天才死信。
 *       不装延迟插件（见 D-32 的取舍）就必须接受这个缺陷。</li>
 *   <li><b>消息可能丢</b>：broker 重启、投递失败、DLX 配置变更都会让某些延迟消息消失。</li>
 * </ol>
 * 扫描的判据很朴素但有效：<b>凡是"该提醒了却还是待发"的记录，补发</b>。
 * 它按 {@code remind_time} 找活，与延迟消息共用 {@link InterviewReminderService#fire} 的乐观占位，
 * 所以两条路径同时命中也不会重复发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewReminderScanner {

    private static final int BATCH = 100;

    private final InterviewReminderService reminderService;
    private final InterviewMapper interviewMapper;

    /** 每 5 分钟补扫一次；启动后延迟 1 分钟，避开服务刚起来时的抖动 */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void scan() {
        try {
            List<InterviewReminder> due = reminderService.dueReminders(LocalDateTime.now(), BATCH);
            if (due.isEmpty()) {
                return;
            }
            int sent = 0;
            int discarded = 0;
            for (InterviewReminder reminder : due) {
                Interview interview = interviewMapper.selectById(reminder.getInterviewId());
                if (!reminderService.stillValid(interview)) {
                    // 面试已取消 / 已开始：这条提醒永远不该发出去，标记作废避免每轮重复扫到
                    reminderService.discard(reminder);
                    discarded++;
                    continue;
                }
                if (reminderService.fire(reminder, interview)) {
                    sent++;
                }
            }
            log.info("面试提醒兜底扫描完成: 待发={} 补发={} 作废={}", due.size(), sent, discarded);
        } catch (Exception e) {
            log.warn("面试提醒兜底扫描失败（下轮重试）: {}", e.getMessage());
        }
    }
}
