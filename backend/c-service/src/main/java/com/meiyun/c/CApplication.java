package com.meiyun.c;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * C 端移动端 BFF（第 9 微服务，8090/SEED 18090）。
 * B/C 隔离红线（DESIGN-C §〇.1）：C 端用户=顾客（customer），token/密钥/鉴权链路全部独立，
 * 不依赖 meiyun-security（B 端 staff JWT/RBAC 一律不进 C 端链路）。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CApplication {
    public static void main(String[] args) {
        SpringApplication.run(CApplication.class, args);
    }

    /** 服务间 REST 调用（审计追加/微信 code2session 外呼）：连接 3s / 读取 5s 超时，避免下游故障拖垮登录链路。 */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        return new RestTemplate(factory);
    }
}
