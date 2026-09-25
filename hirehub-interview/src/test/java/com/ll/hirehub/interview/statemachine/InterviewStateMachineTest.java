package com.ll.hirehub.interview.statemachine;

import com.ll.hirehub.common.exception.BusinessException;
import com.ll.hirehub.common.result.ResultCode;
import com.ll.hirehub.interview.enums.InterviewEvent;
import com.ll.hirehub.interview.enums.InterviewStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 面试状态机（D-28）。这是"面试与投递解耦"的核心，也是纯函数，最适合单测。
 * <p>
 * 为什么值得单测：状态机的价值全在**边界**上 —— 终态能不能被再次推进、谁能推哪个事件、
 * 以及"迁移表与角色规则谁先校验"。端到端脚本能证明"正常路径能走通"，
 * 但把每条非法组合都跑一遍代价太高，那正是单测该做的事。
 */
class InterviewStateMachineTest {

    private final InterviewStateMachine machine = new InterviewStateMachine();

    @Test
    @DisplayName("正常路径：SCHEDULED --CONFIRM--> CONFIRMED --COMPLETE--> COMPLETED")
    void happyPath() {
        assertThat(machine.apply(InterviewStatus.SCHEDULED, InterviewEvent.CONFIRM, "SEEKER"))
                .isEqualTo(InterviewStatus.CONFIRMED);
        assertThat(machine.apply(InterviewStatus.CONFIRMED, InterviewEvent.COMPLETE, "HR"))
                .isEqualTo(InterviewStatus.COMPLETED);
    }

    @Test
    @DisplayName("改期回到 SCHEDULED：必须让求职者重新确认，不能直接保持 CONFIRMED")
    void rescheduleGoesBackToScheduled() {
        assertThat(machine.apply(InterviewStatus.CONFIRMED, InterviewEvent.RESCHEDULE, "HR"))
                .isEqualTo(InterviewStatus.SCHEDULED);
    }

    @ParameterizedTest
    @EnumSource(value = InterviewEvent.class)
    @DisplayName("三个终态都拒绝任何事件（含被拒/取消/已完成）")
    void terminalStatesRejectEverything(InterviewEvent event) {
        for (InterviewStatus terminal : new InterviewStatus[]{
                InterviewStatus.COMPLETED, InterviewStatus.REJECTED, InterviewStatus.CANCELLED}) {
            assertThatThrownBy(() -> machine.apply(terminal, event, "HR"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("非法状态迁移");
        }
    }

    @Test
    @DisplayName("角色规则：CONFIRM / REJECT 只能求职者发起")
    void seekerOnlyEvents() {
        assertThatThrownBy(() -> machine.apply(InterviewStatus.SCHEDULED, InterviewEvent.CONFIRM, "HR"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));
        assertThatThrownBy(() -> machine.apply(InterviewStatus.SCHEDULED, InterviewEvent.REJECT, "HR"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));
    }

    @Test
    @DisplayName("角色规则：COMPLETE / RESCHEDULE 只能 HR 发起")
    void hrOnlyEvents() {
        assertThatThrownBy(() -> machine.apply(InterviewStatus.CONFIRMED, InterviewEvent.COMPLETE, "SEEKER"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));
        assertThatThrownBy(() -> machine.apply(InterviewStatus.CONFIRMED, InterviewEvent.RESCHEDULE, "SEEKER"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));
    }

    @Test
    @DisplayName("CANCEL 双方都可发起（求职者与 HR）")
    void bothActorsCanCancel() {
        assertThat(machine.apply(InterviewStatus.SCHEDULED, InterviewEvent.CANCEL, "SEEKER"))
                .isEqualTo(InterviewStatus.CANCELLED);
        assertThat(machine.apply(InterviewStatus.SCHEDULED, InterviewEvent.CANCEL, "HR"))
                .isEqualTo(InterviewStatus.CANCELLED);
    }

    /**
     * 这条钉的是**校验顺序**：先迁移表、再角色规则。
     * <p>
     * 如果哪天有人把两个判断调换，那么"CONFIRMED 下 HR 发起 REJECT"就会从
     * 「非法状态迁移」变成「无权限」—— 错误码语义变了，调用方和排障都会受影响。
     * 这个顺序是端到端验证时真实踩到过的（见踩坑记录 #37）。
     */
    @Test
    @DisplayName("校验顺序：迁移表先于角色规则（CONFIRMED + REJECT 报非法迁移，而非无权限）")
    void transitionTableIsCheckedBeforeActorRules() {
        assertThatThrownBy(() -> machine.apply(InterviewStatus.CONFIRMED, InterviewEvent.REJECT, "HR"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法状态迁移");
    }

    @Test
    @DisplayName("SCHEDULED 下不能直接完成面试（必须先确认）")
    void cannotCompleteBeforeConfirm() {
        assertThatThrownBy(() -> machine.apply(InterviewStatus.SCHEDULED, InterviewEvent.COMPLETE, "HR"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法状态迁移");
    }
}
