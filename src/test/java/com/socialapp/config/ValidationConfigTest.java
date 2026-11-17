package com.socialapp.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Locale;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The API validator interpolates in English regardless of the requested locale (request/Accept-Language via
 * {@link LocaleContextHolder}) and of the JVM default locale. The baseline (Spring's plain validator) proves the test
 * environment really reproduces the localized messages.
 */
class ValidationConfigTest {

    record Request(@NotBlank String content, @Size(min = 3, max = 30) String username) {}

    private static final Locale POLISH = Locale.forLanguageTag("pl-PL");

    private Locale previousDefault;
    private GenericApplicationContext context;

    @BeforeEach
    void polishEverywhere() {
        previousDefault = Locale.getDefault();
        Locale.setDefault(POLISH);
        LocaleContextHolder.setLocale(POLISH);
        context = new GenericApplicationContext();
        context.refresh();
    }

    @AfterEach
    void restore() {
        Locale.setDefault(previousDefault);
        LocaleContextHolder.resetLocaleContext();
        context.close();
    }

    private static TreeMap<String, String> messages(LocalValidatorFactoryBean validator) {
        var result = new TreeMap<String, String>();
        validator.validate(new Request("  ", "ab")).forEach(v -> result.put(v.getPropertyPath().toString(), v.getMessage()));
        return result;
    }

    @Test
    void baseline_springsPlainValidatorFollowsTheRequestedLocale() {
        try (var plain = new LocalValidatorFactoryBean()) {
            plain.afterPropertiesSet();
            assertThat(messages(plain).get("content")).isNotEqualTo("must not be blank");
        }
    }

    @Test
    void apiValidatorIsEnglishForAPolishRequestLocale() {
        try (var validator = ValidationConfig.defaultValidator(context)) {
            validator.afterPropertiesSet();
            assertThat(messages(validator)).containsExactly(
                    java.util.Map.entry("content", "must not be blank"),
                    java.util.Map.entry("username", "size must be between 3 and 30"));
        }
    }

    @Test
    void apiValidatorIsEnglishOutsideARequestOnAPolishJvm() {
        LocaleContextHolder.resetLocaleContext(); // no request locale: Spring would use Locale.getDefault() = pl
        try (var validator = ValidationConfig.defaultValidator(context)) {
            validator.afterPropertiesSet();
            assertThat(messages(validator).get("content")).isEqualTo("must not be blank");
        }
    }
}
