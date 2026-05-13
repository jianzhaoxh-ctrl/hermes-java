package com.hermes.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties
@EnableScheduling
@EnableAsync(proxyTargetClass = true)
public class HermesAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(HermesAgentApplication.class, args);
        System.out.println("Hermes Agent started successfully!");
    }
}