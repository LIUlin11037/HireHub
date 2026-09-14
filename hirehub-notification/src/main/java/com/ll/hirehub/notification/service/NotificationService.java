package com.ll.hirehub.notification.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ll.hirehub.notification.entity.Notification;
import com.ll.hirehub.notification.entity.NotificationReceiver;
import com.ll.hirehub.notification.mapper.NotificationMapper;
import com.ll.hirehub.notification.mapper.NotificationReceiverMapper;
import com.ll.hirehub.notification.vo.NotificationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationMapper notificationMapper;
    private final NotificationReceiverMapper receiverMapper;

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
    }
}
