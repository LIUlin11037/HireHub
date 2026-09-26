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
 * 面试提醒兜底扫描（见 D-32 / D-38）。
 * <p>
 * <b>主路径</b>是 {@code RedisDelayQueue} + {@code InterviewReminderPoller}（秒级到点即发）。
 * 本扫描器是**兜底**，覆盖主路径覆盖不到的情况：
 * <ol>
 *   <li><b>延迟项丢了</b>：轮询器已用 {@code ZREM} 认领、但还没发出通知就崩了；
 *       或者排布时写 Redis 失败（那时只记了一条 error 日志）。</li>
 *   <li><b>服务停机期间到点</b>：实例重启那几分钟内到点的提醒没人触发。</li>
 *   <li><b>任何未来的实现漏洞</b>：判据是"该提醒了却还是待发"，与"延迟怎么实现的"无关。</li>
 * </ol>
 * 它按 {@code remind_time} 找活，与主路径共用 {@link InterviewReminderService#fire} 的乐观占位，
 * 所以两条路径同时命中也不会重复发。
 * <p>
 * （历史上这里兜的是「RabbitMQ 消息级 TTL 的队头阻塞」，那个缺陷已在 D-38 里根治，
 * 但**兜底本身要保留** —— 理由与上面第 1、2 条无关，而是"主路径永远可能失败"。）
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
