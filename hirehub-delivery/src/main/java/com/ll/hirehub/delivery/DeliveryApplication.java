package com.ll.hirehub.delivery;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.ll.hirehub")
@MapperScan("com.ll.hirehub.delivery.mapper")
@EnableFeignClients(basePackages = "com.ll.hirehub.api")
@EnableScheduling // 二期：本地消息表定时投递 / 对账补偿 / 历史清理（见 §6.2）
public class DeliveryApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeliveryApplication.class, args);
    }
}
