package com.abc.sourceservice;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
@MapperScan("com.abc.sourceservice.mapper")
public class SourceApplication {
    public static void main(String[] args) {
        SpringApplication.run(SourceApplication.class, args);
    }
}
