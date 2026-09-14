package com.ll.hirehub.notification.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notification_receiver")
public class NotificationReceiver {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long notificationId;
    private Long receiverId;
    private Integer readStatus;
    private LocalDateTime readTime;
}
