package com.ll.hirehub.delivery.controller;

import com.ll.hirehub.common.exception.BusinessException;
import com.ll.hirehub.common.result.Result;
import com.ll.hirehub.delivery.dto.ApplyRequest;
import com.ll.hirehub.delivery.dto.EventRequest;
import com.ll.hirehub.delivery.entity.Delivery;
import com.ll.hirehub.delivery.entity.DeliveryResume;
import com.ll.hirehub.delivery.enums.DeliveryEvent;
import com.ll.hirehub.delivery.service.DeliveryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 投递接口（见架构文档 §7 / D-27）：
 * 前端只发事件、不传目标状态；status 对前端只读，由状态机派生。
 */
@RestController
@RequestMapping("/api/delivery")
@RequiredArgsConstructor
public class DeliveryController {

    private final DeliveryService deliveryService;

    @PostMapping
    public Result<Long> apply(@RequestHeader("X-User-Id") Long userId,
                              @Valid @RequestBody ApplyRequest req) {
        return Result.ok(deliveryService.apply(userId, req));
    }

    @GetMapping("/{id}")
    public Result<Delivery> get(@RequestHeader("X-User-Id") Long userId, @PathVariable Long id) {
        return Result.ok(deliveryService.get(userId, id));
    }

    @GetMapping("/{id}/resume-snapshot")
    public Result<DeliveryResume> snapshot(@RequestHeader("X-User-Id") Long userId, @PathVariable Long id) {
        return Result.ok(deliveryService.getSnapshot(userId, id));
    }

    @PostMapping("/{id}/events")
    public Result<Void> event(@RequestHeader("X-User-Id") Long userId,
                              @PathVariable Long id,
                              @Valid @RequestBody EventRequest req) {
        DeliveryEvent event;
        try {
            event = DeliveryEvent.valueOf(req.getEvent());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("非法事件：" + req.getEvent());
        }
        deliveryService.applyEvent(userId, id, event);
        return Result.ok();
    }

    @GetMapping("/mine")
    public Result<List<Delivery>> mine(@RequestHeader("X-User-Id") Long userId) {
        return Result.ok(deliveryService.mine(userId));
    }

    @GetMapping("/received")
    public Result<List<Delivery>> received(@RequestHeader("X-User-Id") Long userId,
                                           @RequestHeader("X-Company-Id") Long companyId) {
        return Result.ok(deliveryService.received(companyId, userId));
    }
}
