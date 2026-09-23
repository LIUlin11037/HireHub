package com.ll.hirehub.notification.controller;

import com.ll.hirehub.common.result.Result;
import com.ll.hirehub.notification.service.NotificationService;
import com.ll.hirehub.notification.vo.NotificationVO;
import com.ll.hirehub.notification.ws.WsTicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final WsTicketService wsTicketService;

    @GetMapping("/list")
    public Result<List<NotificationVO>> list(@RequestHeader("X-User-Id") Long userId) {
        return Result.ok(notificationService.list(userId));
    }

    /**
     * 换取 WebSocket 一次性票据（见 D-34）。
     * <p>
     * 走网关鉴权（身份来自 X-User-Id），客户端拿到 ticket 后连
     * {@code ws://<gateway>/ws/notification?ticket=xxx}。
     * 之所以不把 accessToken 直接放进 WS 的 URL：URL 会被日志记录，
     * 而 ticket 是短时且一次性的，泄漏窗口极小。
     */
    @PostMapping("/ws-ticket")
    public Result<String> wsTicket(@RequestHeader("X-User-Id") Long userId) {
        return Result.ok(wsTicketService.issue(userId));
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
