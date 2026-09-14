package com.ll.hirehub.delivery.statemachine;

import com.ll.hirehub.common.exception.BusinessException;
import com.ll.hirehub.common.result.ResultCode;
import com.ll.hirehub.delivery.enums.DeliveryEvent;
import com.ll.hirehub.delivery.enums.DeliveryStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 投递状态机：事件驱动 + 迁移表 + 权限绑定在事件上（见 D-27）
 * 迁移 = 查表，不用 if/else；新增迁移只加一行表项。
 */
@Component
public class DeliveryStateMachine {

    /** 合法迁移白名单：当前状态 → 可发起的事件 */
    private static final Map<DeliveryStatus, Set<DeliveryEvent>> TRANSITIONS = Map.of(
            DeliveryStatus.PENDING, Set.of(DeliveryEvent.VIEW, DeliveryEvent.CONTACT,
                    DeliveryEvent.INVITE_INTERVIEW, DeliveryEvent.CANCEL),
            DeliveryStatus.VIEWED, Set.of(DeliveryEvent.CONTACT, DeliveryEvent.INVITE_INTERVIEW, DeliveryEvent.CANCEL),
            DeliveryStatus.COMMUNICATING, Set.of(DeliveryEvent.INVITE_INTERVIEW, DeliveryEvent.REJECT, DeliveryEvent.CANCEL),
            DeliveryStatus.INTERVIEW, Set.of(DeliveryEvent.SEND_OFFER, DeliveryEvent.REJECT, DeliveryEvent.CANCEL),
            DeliveryStatus.OFFER, Set.of(DeliveryEvent.HIRE, DeliveryEvent.REJECT, DeliveryEvent.CANCEL),
            DeliveryStatus.HIRED, Set.of(),
            DeliveryStatus.REJECTED, Set.of(),
            DeliveryStatus.CANCELLED, Set.of()
    );

    /** 权限绑定在事件上：谁可以发起哪个事件 */
    private static final Map<DeliveryEvent, Set<String>> ACTOR_RULES = Map.of(
            DeliveryEvent.VIEW, Set.of("HR"),
            DeliveryEvent.CONTACT, Set.of("HR"),
            DeliveryEvent.INVITE_INTERVIEW, Set.of("HR"),
            DeliveryEvent.SEND_OFFER, Set.of("HR"),
            DeliveryEvent.REJECT, Set.of("HR"),
            DeliveryEvent.HIRE, Set.of("HR"),
            DeliveryEvent.CANCEL, Set.of("SEEKER")
    );

    public DeliveryStatus apply(DeliveryStatus current, DeliveryEvent event, String actor) {
        if (!TRANSITIONS.getOrDefault(current, Set.of()).contains(event)) {
            throw new BusinessException("非法状态迁移：" + current + " --" + event + "-->");
        }
        if (!ACTOR_RULES.get(event).contains(actor)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return event.getTarget();
    }
}
