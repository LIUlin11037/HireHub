package com.ll.hirehub.notification.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ll.hirehub.notification.entity.Notification;
import com.ll.hirehub.notification.entity.NotificationReceiver;
import com.ll.hirehub.notification.mapper.NotificationMapper;
import com.ll.hirehub.notification.mapper.NotificationReceiverMapper;
import com.ll.hirehub.notification.vo.NotificationVO;
import com.ll.hirehub.notification.ws.WsPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationMapper notificationMapper;
    private final NotificationReceiverMapper receiverMapper;
    private final WsPushService wsPushService;

    public List<NotificationVO> list(Long userId) {
        return receiverMapper.selectByReceiver(userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void read(Long userId, Long notificationId) {
        // 只能标记发给自己的通知
        receiverMapper.update(null, new LambdaUpdateWrapper<NotificationReceiver>()
                .eq(NotificationReceiver::getNotificationId, notificationId)
                .eq(NotificationReceiver::getReceiverId, userId)
                .set(NotificationReceiver::getReadStatus, 1)
                .set(NotificationReceiver::getReadTime, LocalDateTime.now()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void readAll(Long userId) {
        receiverMapper.update(null, new LambdaUpdateWrapper<NotificationReceiver>()
                .eq(NotificationReceiver::getReceiverId, userId)
                .set(NotificationReceiver::getReadStatus, 1)
                .set(NotificationReceiver::getReadTime, LocalDateTime.now()));
    }

    public Long unreadCount(Long userId) {
        return receiverMapper.selectCount(new LambdaQueryWrapper<NotificationReceiver>()
                .eq(NotificationReceiver::getReceiverId, userId)
                .eq(NotificationReceiver::getReadStatus, 0));
    }

    /** 创建通知（二期由 MQ 消费者调用；一期走 /internal/notify 测试） */
    @Transactional(rollbackFor = Exception.class)
    public void createInternal(String type, String title, String content, List<Long> receiverIds) {
        Notification n = new Notification();
        n.setType(type);
        n.setTitle(title);
        n.setContent(content);
        notificationMapper.insert(n);
        for (Long receiverId : receiverIds) {
            NotificationReceiver r = new NotificationReceiver();
            r.setNotificationId(n.getId());
            r.setReceiverId(receiverId);
            r.setReadStatus(0);
            receiverMapper.insert(r);
        }
        // WebSocket 推送放到事务提交后（见 D-34 / 踩坑记录 #14）：
        // 推送是外部副作用，不参与回滚。若在提交前推，"通知落库失败但用户已经收到推送"，
        // 用户点进去什么都没有——比"晚几十毫秒收到"糟糕得多。
        afterCommit(() -> receiverIds.forEach(receiverId -> pushNotification(receiverId, n)));
    }

    /** 实时推送一条通知（含未读角标数，前端收到无需再请求） */
    private void pushNotification(Long receiverId, Notification n) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", n.getId());
            body.put("type", n.getType());
            body.put("title", n.getTitle());
            body.put("content", n.getContent());

            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("type", "NOTIFICATION");
            frame.put("payload", body);
            frame.put("unreadCount", unreadCount(receiverId));
            wsPushService.pushToUser(receiverId, frame);
        } catch (Exception e) {
            // 推送失败不影响通知本身：用户下次拉列表照样能看到
            log.warn("通知实时推送失败（已落库，不影响可见性）: receiverId={} err={}",
                    receiverId, e.getMessage());
        }
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
