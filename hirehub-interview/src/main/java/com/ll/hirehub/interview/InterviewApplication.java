package com.ll.hirehub.interview;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.ll.hirehub")
@MapperScan("com.ll.hirehub.interview.mapper")
@EnableFeignClients(basePackages = "com.ll.hirehub.api")
@EnableScheduling // 三期：面试提醒兜底扫描（见 D-32）
public class InterviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(InterviewApplication.class, args);
    }
}
