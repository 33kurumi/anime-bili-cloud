package com.abc.danmakuservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = {"com.abc.danmakuservice", "com.abc.common"})
@EnableDiscoveryClient
public class DanmakuApplication {
    public static void main(String[] args) {
        SpringApplication.run(DanmakuApplication.class, args);
    }
}
