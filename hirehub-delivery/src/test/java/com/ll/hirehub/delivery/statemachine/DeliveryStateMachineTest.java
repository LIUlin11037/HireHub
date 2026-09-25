package com.ll.hirehub.delivery.statemachine;

import com.ll.hirehub.common.exception.BusinessException;
import com.ll.hirehub.common.result.ResultCode;
import com.ll.hirehub.delivery.enums.DeliveryEvent;
import com.ll.hirehub.delivery.enums.DeliveryStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 投递状态机（D-27）。端到端脚本已经跑通过主链路，
 * 这里补齐它跑不到的部分：**每一条非法迁移**、以及事件级的角色绑定。
 */
class DeliveryStateMachineTest {

    private final DeliveryStateMachine machine = new DeliveryStateMachine();

    @Test
    @DisplayName("主链路：PENDING -> VIEWED -> COMMUNICATING -> INTERVIEW -> OFFER -> HIRED")
    void happyPath() {
        assertThat(machine.apply(DeliveryStatus.PENDING, DeliveryEvent.VIEW, "HR")).isEqualTo(DeliveryStatus.VIEWED);
        assertThat(machine.apply(DeliveryStatus.VIEWED, DeliveryEvent.CONTACT, "HR"))
                .isEqualTo(DeliveryStatus.COMMUNICATING);
        assertThat(machine.apply(DeliveryStatus.COMMUNICATING, DeliveryEvent.INVITE_INTERVIEW, "HR"))
                .isEqualTo(DeliveryStatus.INTERVIEW);
        assertThat(machine.apply(DeliveryStatus.INTERVIEW, DeliveryEvent.SEND_OFFER, "HR"))
                .isEqualTo(DeliveryStatus.OFFER);
        assertThat(machine.apply(DeliveryStatus.OFFER, DeliveryEvent.HIRE, "HR")).isEqualTo(DeliveryStatus.HIRED);
    }

    @Test
    @DisplayName("跳级允许（PENDING 可直接邀面试），但终态一律拒绝")
    void terminalStatesRejectEverything() {
        for (DeliveryStatus terminal : new DeliveryStatus[]{
                DeliveryStatus.HIRED, DeliveryStatus.REJECTED, DeliveryStatus.CANCELLED}) {
            for (DeliveryEvent event : DeliveryEvent.values()) {
                assertThatThrownBy(() -> machine.apply(terminal, event, "HR"))
                        .as("%s + %s", terminal, event)
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining("非法状态迁移");
            }
        }
    }

    @Test
    @DisplayName("★ CANCEL 只能求职者发起：HR 不能替求职者撤回投递")
    void onlySeekerCanCancel() {
        assertThatThrownBy(() -> machine.apply(DeliveryStatus.PENDING, DeliveryEvent.CANCEL, "HR"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));
    }

    @Test
    @DisplayName("★ 求职者不能推进流程：只有 CANCEL 是求职者可发起的")
    void seekerCannotAdvanceTheFunnel() {
        // 关键：每个事件都必须配一个「该事件本身是合法迁移」的状态。
        // 若一律拿 PENDING 去试 SEND_OFFER，会先被迁移表挡掉（非法迁移），
        // 于是测到的是迁移表而不是角色规则 —— 这个坑在端到端脚本里已经踩过一次（见踩坑 #37）。
        Map<DeliveryEvent, DeliveryStatus> legalFrom = Map.of(
                DeliveryEvent.VIEW, DeliveryStatus.PENDING,
                DeliveryEvent.CONTACT, DeliveryStatus.PENDING,
                DeliveryEvent.INVITE_INTERVIEW, DeliveryStatus.PENDING,
                DeliveryEvent.SEND_OFFER, DeliveryStatus.INTERVIEW,
                DeliveryEvent.REJECT, DeliveryStatus.COMMUNICATING,
                DeliveryEvent.HIRE, DeliveryStatus.OFFER);

        legalFrom.forEach((event, state) ->
                assertThatThrownBy(() -> machine.apply(state, event, "SEEKER"))
                        .as("SEEKER + %s from %s（迁移合法，只应因角色被拒）", event, state)
                        .isInstanceOfSatisfying(BusinessException.class,
                                e -> assertThat(e.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode())));
    }

    @Test
    @DisplayName("校验顺序：迁移表先于角色规则（PENDING + SEND_OFFER 报非法迁移，而非无权限）")
    void transitionTableIsCheckedBeforeActorRules() {
        // HR 发 SEND_OFFER 本身角色是允许的，但 PENDING 不允许这个迁移 ——
        // 所以这里必须是「非法状态迁移」，不是 403。顺序被改坏了这条会红。
        assertThatThrownBy(() -> machine.apply(DeliveryStatus.PENDING, DeliveryEvent.SEND_OFFER, "HR"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法状态迁移");
    }

    @Test
    @DisplayName("OFFER 之后求职者只能拒（业务上即 CANCEL/REJECT 由各自角色发起）")
    void offerIsNotTerminal() {
        assertThat(machine.apply(DeliveryStatus.OFFER, DeliveryEvent.CANCEL, "SEEKER"))
                .isEqualTo(DeliveryStatus.CANCELLED);
        assertThat(machine.apply(DeliveryStatus.OFFER, DeliveryEvent.REJECT, "HR"))
                .isEqualTo(DeliveryStatus.REJECTED);
    }

    @Test
    @DisplayName("COMMUNICATING 不能直接发 Offer（必须先邀面试）")
    void cannotSendOfferFromCommunicating() {
        assertThatThrownBy(() -> machine.apply(DeliveryStatus.COMMUNICATING, DeliveryEvent.SEND_OFFER, "HR"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法状态迁移");
    }
}
