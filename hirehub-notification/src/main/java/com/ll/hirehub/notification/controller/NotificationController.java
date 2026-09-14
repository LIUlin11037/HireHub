package com.ll.hirehub.notification.controller;

import com.ll.hirehub.common.result.Result;
import com.ll.hirehub.notification.service.NotificationService;
import com.ll.hirehub.notification.vo.NotificationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/notification")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping("/list")
    public Result<List<NotificationVO>> list(@RequestHeader("X-User-Id") Long userId) {
        return Result.ok(notificationService.list(userId));
    }

    @PutMapping("/{id}/read")
    public Result<Void> read(@RequestHeader("X-User-Id") Long userId, @PathVariable Long id) {
        notificationService.read(userId, id);
        return Result.ok();
    }

    @PutMapping("/read-all")
    public Result<Void> readAll(@RequestHeader("X-User-Id") Long userId) {
        notificationService.readAll(userId);
        return Result.ok();
    }

    @GetMapping("/unread-count")
    public Result<Long> unreadCount(@RequestHeader("X-User-Id") Long userId) {
        return Result.ok(notificationService.unreadCount(userId));
    }
}
