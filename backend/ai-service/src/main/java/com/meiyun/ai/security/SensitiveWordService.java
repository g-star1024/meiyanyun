package com.meiyun.ai.security;

import com.meiyun.ai.domain.AiGlobalCfgRepository;
import com.meiyun.ai.domain.AiSensitiveWord;
import com.meiyun.ai.domain.AiSensitiveWordRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

/**
 * 出站提示词合规过滤：读 ai_global_cfg.sensitive_check 总开关，
 * BANNED 违禁内容 / INJECTION 越权提示词（prompt injection）命中即在 LLM 出站前 400 中文拒绝。
 * 命中拦截属前置校验，不落 ai_invoke_log、不占调用配额。
 */
@Service
public class SensitiveWordService {

    public static final String BANNED = "BANNED";
    public static final String INJECTION = "INJECTION";

    private final AiSensitiveWordRepository wordRepo;
    private final AiGlobalCfgRepository cfgRepo;

    public SensitiveWordService(AiSensitiveWordRepository wordRepo, AiGlobalCfgRepository cfgRepo) {
        this.wordRepo = wordRepo;
        this.cfgRepo = cfgRepo;
    }

    @Transactional(readOnly = true)
    public void screen(String text) {
        boolean checkOn = cfgRepo.findById(1).map(c -> !Boolean.FALSE.equals(c.getSensitiveCheck())).orElse(true);
        if (!checkOn || text == null || text.isBlank()) {
            return;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        List<AiSensitiveWord> words = wordRepo.findByEnabledTrue();
        for (AiSensitiveWord w : words) {
            String kw = w.getWord() == null ? "" : w.getWord().trim();
            if (!kw.isEmpty() && lower.contains(kw.toLowerCase(Locale.ROOT))) {
                if (INJECTION.equals(w.getCategory())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "输入内容命中越权提示词防护（" + mask(kw) + "），已按安全策略拦截，请调整后重试");
                }
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "输入内容包含平台违禁内容（" + mask(kw) + "），已按合规策略拦截，请调整后重试");
            }
        }
    }

    private static String mask(String kw) {
        if (kw.length() <= 2) {
            return kw.charAt(0) + "*";
        }
        return kw.substring(0, 1) + "*".repeat(Math.max(1, kw.length() - 2)) + kw.charAt(kw.length() - 1);
    }
}
