package com.meiyun.txn;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
public class TxnApplication {
    public static void main(String[] args) {
        SpringApplication.run(TxnApplication.class, args);
    }

    /** 服务间调用（org/store 名解析、audit 审计）专用 RestTemplate，带超时防被调方不可用拖垮本服务。 */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3000);
        f.setReadTimeout(5000);
        return new RestTemplate(f);
    }
}
