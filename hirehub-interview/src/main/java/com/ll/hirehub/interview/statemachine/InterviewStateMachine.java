package com.ll.hirehub.interview.statemachine;

import com.ll.hirehub.common.exception.BusinessException;
import com.ll.hirehub.common.result.ResultCode;
import com.ll.hirehub.interview.enums.InterviewEvent;
import com.ll.hirehub.interview.enums.InterviewStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 面试状态机（见 D-28）：与投递状态机同模式（事件驱动 + 迁移表 + 权限绑在事件上）。
 * 改期（RESCHEDULE）回 SCHEDULED，要求求职者重新确认。
 */
@Component
public class InterviewStateMachine {

    private static final Map<InterviewStatus, Set<InterviewEvent>> TRANSITIONS = Map.of(
            InterviewStatus.SCHEDULED, Set.of(InterviewEvent.CONFIRM, InterviewEvent.REJECT, InterviewEvent.CANCEL),
            InterviewStatus.CONFIRMED, Set.of(InterviewEvent.COMPLETE, InterviewEvent.CANCEL, InterviewEvent.RESCHEDULE),
            InterviewStatus.COMPLETED, Set.of(),
            InterviewStatus.REJECTED, Set.of(),
            InterviewStatus.CANCELLED, Set.of()
    );

    private static final Map<InterviewEvent, Set<String>> ACTOR_RULES = Map.of(
            InterviewEvent.CONFIRM, Set.of("SEEKER"),
            InterviewEvent.REJECT, Set.of("SEEKER"),
            InterviewEvent.CANCEL, Set.of("SEEKER", "HR"),
            InterviewEvent.COMPLETE, Set.of("HR"),
            InterviewEvent.RESCHEDULE, Set.of("HR")
    );

    public InterviewStatus apply(InterviewStatus current, InterviewEvent event, String actor) {
        if (!TRANSITIONS.getOrDefault(current, Set.of()).contains(event)) {
            throw new BusinessException("非法状态迁移：" + current + " --" + event + "-->");
        }
        if (!ACTOR_RULES.get(event).contains(actor)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return event.getTarget();
    }
}
