package com.ll.hirehub.delivery.enums;

/**
 * 投递事件（见 D-27）：每个事件携带目标状态，前端只发事件、不传目标状态。
 */
public enum DeliveryEvent {

    VIEW(DeliveryStatus.VIEWED),
    CONTACT(DeliveryStatus.COMMUNICATING),
    INVITE_INTERVIEW(DeliveryStatus.INTERVIEW),
    SEND_OFFER(DeliveryStatus.OFFER),
    REJECT(DeliveryStatus.REJECTED),
    HIRE(DeliveryStatus.HIRED),
    CANCEL(DeliveryStatus.CANCELLED);

    private final DeliveryStatus target;

    DeliveryEvent(DeliveryStatus target) {
        this.target = target;
    }

    public DeliveryStatus getTarget() {
        return target;
    }
}
