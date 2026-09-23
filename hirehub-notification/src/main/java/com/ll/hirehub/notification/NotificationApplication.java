package com.ll.hirehub.notification;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.ll.hirehub")
@MapperScan("com.ll.hirehub.notification.mapper")
@EnableScheduling // 三期：WebSocket 心跳保活（见 D-34）
public class NotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
