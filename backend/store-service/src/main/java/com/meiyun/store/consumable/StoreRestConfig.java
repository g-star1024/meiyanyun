package com.meiyun.store.consumable;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * store-service 服务间调用 RestTemplate（B5：耗材出库回调后的审计追加）。
 * 带超时防被调方（audit-service）不可用拖垮本服务；与其他服务 Application 内范本一致。
 */
@Configuration
public class StoreRestConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3000);
        f.setReadTimeout(5000);
        return new RestTemplate(f);
    }
}
