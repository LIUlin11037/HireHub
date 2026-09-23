package com.ll.hirehub.resume;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.ll.hirehub")
@MapperScan("com.ll.hirehub.resume.mapper")
@EnableFeignClients(basePackages = "com.ll.hirehub.api")   // 三期：人才库要对 company 校验"已认证企业的在职 HR"（D-23）
@EnableScheduling // 三期：resume_index 对账兜底
public class ResumeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResumeApplication.class, args);
    }
}
