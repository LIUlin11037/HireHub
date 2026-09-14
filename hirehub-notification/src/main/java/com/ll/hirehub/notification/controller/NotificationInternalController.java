package com.ll.hirehub.notification.controller;

import com.ll.hirehub.common.result.Result;
import com.ll.hirehub.notification.dto.NotifyRequest;
import com.ll.hirehub.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部接口：创建通知（二期由 RabbitMQ 消费者调用，一期用于测试）
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class NotificationInternalController {

    private final NotificationService notificationService;

    @PostMapping("/notify")
    public Result<Void> notify(@RequestBody NotifyRequest req) {
        notificationService.createInternal(req.getType(), req.getTitle(), req.getContent(), req.getReceiverIds());
        return Result.ok();
    }
}
