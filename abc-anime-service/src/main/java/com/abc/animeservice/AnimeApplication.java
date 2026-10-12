package com.abc.animeservice;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = {"com.abc.animeservice", "com.abc.common"})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.abc.animeservice.feign")
@MapperScan("com.abc.animeservice.mapper")
public class AnimeApplication {
    public static void main(String[] args) {
        SpringApplication.run(AnimeApplication.class, args);
    }
}
