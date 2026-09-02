package com.meiyun.marketing;

import com.meiyun.marketing.audit.AuditRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ForbiddenWordService} 业务逻辑单测（纯 Mockito，不走 Spring/缓存代理）：
 * 覆盖空表回落内置词库、命中格式、新增幂等（重复/停用复活）、非法类别中文错误、启停幂等、删除幂等。
 */
@ExtendWith(MockitoExtension.class)
class ForbiddenWordServiceTest {

    @Mock
    ForbiddenWordRepository repo;
    @Mock
    ForbiddenWordCatalog catalog;
    @Mock
    AuditRecorder audit;

    ForbiddenWordService service;

    @BeforeEach
    void setUp() {
        service = new ForbiddenWordService(repo, catalog, audit);
    }

    @Test
    void check_falls_back_to_builtin_when_db_empty() {
        when(catalog.enabledCategories()).thenReturn(Map.of());
        // “根治” 为内置医疗承诺词
        List<String> hits = service.check("本店新品根治痘痘");
        assertThat(hits).contains("医疗承诺:根治");
    }

    @Test
    void check_uses_db_words_and_returns_category_word_format() {
        when(catalog.enabledCategories())
                .thenReturn(Map.of("虚假宣传", List.of("内部价", "稳赚")));
        assertThat(service.check("内部价优惠")).containsExactly("虚假宣传:内部价");
        assertThat(service.check("正常文案")).isEmpty();
        assertThat(service.check("")).isEmpty();
        assertThat(service.check(null)).isEmpty();
    }

    @Test
    void categories_falls_back_when_db_empty() {
        when(catalog.enabledCategories()).thenReturn(Map.of());
        assertThat(service.categories()).containsKeys("绝对化用语", "医疗承诺", "虚假宣传", "低俗诱导");
    }

    @Test
    void create_rejects_illegal_category_with_chinese_error() {
        assertThatThrownBy(() -> service.create(new ForbiddenWordService.WordCmd("其他类", "特效")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("违禁词类别不合法");
        verify(repo, never()).save(any());
    }

    @Test
    void create_rejects_blank_word() {
        assertThatThrownBy(() -> service.create(new ForbiddenWordService.WordCmd("虚假宣传", "  ")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("违禁词不可为空");
    }

    @Test
    void create_duplicate_enabled_word_is_idempotent_no_save_no_audit() {
        ForbiddenWord existing = word(1L, "虚假宣传", "内部价", true);
        when(repo.findByCategoryAndWord("虚假宣传", "内部价")).thenReturn(Optional.of(existing));
        ForbiddenWord result = service.create(new ForbiddenWordService.WordCmd("虚假宣传", " 内部价 "));
        assertThat(result).isSameAs(existing);
        verify(repo, never()).save(any());
        verify(audit, never()).record(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void create_duplicate_disabled_word_re_enables_and_audits() {
        ForbiddenWord existing = word(2L, "低俗诱导", "私密", false);
        when(repo.findByCategoryAndWord("低俗诱导", "私密")).thenReturn(Optional.of(existing));
        when(repo.save(existing)).thenAnswer(inv -> inv.getArgument(0));
        ForbiddenWord result = service.create(new ForbiddenWordService.WordCmd("低俗诱导", "私密"));
        assertThat(result.getEnabled()).isTrue();
        verify(audit).record(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.eq("ENABLE"), anyString());
    }

    @Test
    void create_new_word_persists_and_audits() {
        when(repo.findByCategoryAndWord("虚假宣传", "特效")).thenReturn(Optional.empty());
        when(repo.save(any(ForbiddenWord.class))).thenAnswer(inv -> {
            ForbiddenWord w = inv.getArgument(0);
            w.setWordId(10L);
            return w;
        });
        ForbiddenWord result = service.create(new ForbiddenWordService.WordCmd("虚假宣传", "特效"));
        assertThat(result.getWordId()).isEqualTo(10L);
        assertThat(result.getEnabled()).isTrue();
        verify(audit).record(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.eq("CREATE"), anyString());
    }

    @Test
    void toggle_idempotent_when_state_unchanged() {
        when(repo.findById(5L)).thenReturn(Optional.of(word(5L, "绝对化用语", "最", true)));
        assertThat(service.toggle(5L, true)).isFalse();
        verify(repo, never()).save(any());
        verify(audit, never()).record(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void toggle_changes_state_and_audits() {
        ForbiddenWord w = word(5L, "绝对化用语", "最", true);
        when(repo.findById(5L)).thenReturn(Optional.of(w));
        assertThat(service.toggle(5L, false)).isTrue();
        assertThat(w.getEnabled()).isFalse();
        verify(audit).record(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.eq("DISABLE"), anyString());
    }

    @Test
    void toggle_not_found_throws_404_chinese() {
        when(repo.findById(404L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.toggle(404L, false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("违禁词不存在");
    }

    @Test
    void delete_missing_returns_false_idempotent() {
        when(repo.findById(404L)).thenReturn(Optional.empty());
        assertThat(service.delete(404L)).isFalse();
        verify(repo, never()).delete(any());
    }

    @Test
    void delete_existing_returns_true_and_audits() {
        ForbiddenWord w = word(8L, "低俗诱导", "约炮", true);
        when(repo.findById(8L)).thenReturn(Optional.of(w));
        assertThat(service.delete(8L)).isTrue();
        verify(repo).delete(w);
        verify(audit).record(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.eq("DELETE"), anyString());
    }

    private ForbiddenWord word(Long id, String category, String text, boolean enabled) {
        ForbiddenWord w = new ForbiddenWord();
        w.setWordId(id);
        w.setCategory(category);
        w.setWord(text);
        w.setEnabled(enabled);
        return w;
    }
}
