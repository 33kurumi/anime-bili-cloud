package com.abc.aiservice;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = {"com.abc.aiservice", "com.abc.common"})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.abc.aiservice.feign")
@MapperScan("com.abc.aiservice.mapper")
public class AiApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiApplication.class, args);
    }
}
