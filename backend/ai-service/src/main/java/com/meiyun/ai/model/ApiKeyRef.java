package com.meiyun.ai.model;

import com.meiyun.ai.domain.AiProvider;
import com.meiyun.ai.security.ApiKeyCipher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 供应商 API Key 解密读取的唯一入口；明文 Key 仅在内存中短暂存在用于出站调用，不记录日志。
 */
@Component
public class ApiKeyRef {

    private final ApiKeyCipher cipher;

    public ApiKeyRef(ApiKeyCipher cipher) {
        this.cipher = cipher;
    }

    public String decrypt(AiProvider provider) {
        if (provider.getApiKeyCipher() == null || provider.getApiKeyCipher().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "供应商「" + provider.getProviderName() + "」尚未配置 API 密钥，请先在模型接入页录入");
        }
        return cipher.decrypt(provider.getApiKeyCipher());
    }
}
