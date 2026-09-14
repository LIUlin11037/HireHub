package com.ll.hirehub.interview.enums;

/**
 * 面试事件（见 D-28）
 */
public enum InterviewEvent {

    CONFIRM(InterviewStatus.CONFIRMED),
    REJECT(InterviewStatus.REJECTED),
    CANCEL(InterviewStatus.CANCELLED),
    COMPLETE(InterviewStatus.COMPLETED),
    RESCHEDULE(InterviewStatus.SCHEDULED);

    private final InterviewStatus target;

    InterviewEvent(InterviewStatus target) {
        this.target = target;
    }

    public InterviewStatus getTarget() {
        return target;
    }
}
