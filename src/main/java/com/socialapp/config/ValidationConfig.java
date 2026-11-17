package com.socialapp.config;

import jakarta.validation.MessageInterpolator;
import org.springframework.boot.validation.MessageInterpolatorFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Locale;

/**
 * Bean Validation messages are API error text and must be deterministic English, whatever the client's
 * {@code Accept-Language} or the JVM's default locale ({@code -Duser.language}, the OS) is.
 *
 * <p>By default Spring interpolates messages in {@code LocaleContextHolder}'s locale (the request locale, falling back
 * to {@code Locale.getDefault()}), and Hibernate Validator ships translations (e.g. Polish "nie może być odstępem").
 * Two layers make it fixed:
 * <ul>
 *   <li>{@code spring.web.locale-resolver: fixed} + {@code spring.web.locale: en} (application.yml): the request
 *       locale is always English, for every locale-aware Spring message;</li>
 *   <li>this validator ignores the locale it is handed and always interpolates in {@link #API_LOCALE}, so validation
 *       outside a web request (no request locale -> JVM default) is English too. Hibernate Validator's (empty)
 *       {@code ValidationMessages_en} bundle makes {@code ResourceBundle} stop at the English base bundle instead of
 *       falling back to the JVM default locale's translation.</li>
 * </ul>
 * Replaces Spring Boot's {@code defaultValidator} (same name, same {@code MessageSource}-aware interpolator), which
 * Spring MVC ({@code @Valid}) and method validation pick up.
 */
@Configuration(proxyBeanMethods = false)
public class ValidationConfig {

    /** Locale of every validation message the API returns. */
    public static final Locale API_LOCALE = Locale.ENGLISH;

    @Bean
    public static LocalValidatorFactoryBean defaultValidator(ApplicationContext applicationContext) {
        var factory = new LocalValidatorFactoryBean();
        MessageInterpolator interpolator = new MessageInterpolatorFactory(applicationContext).getObject();
        factory.setMessageInterpolator(new FixedLocaleMessageInterpolator(interpolator, API_LOCALE));
        return factory;
    }

    /** Delegates with a fixed locale, ignoring the one requested (request/JVM locale). */
    record FixedLocaleMessageInterpolator(MessageInterpolator delegate, Locale locale) implements MessageInterpolator {

        @Override
        public String interpolate(String messageTemplate, Context context) {
            return delegate.interpolate(messageTemplate, context, locale);
        }

        @Override
        public String interpolate(String messageTemplate, Context context, Locale ignored) {
            return delegate.interpolate(messageTemplate, context, locale);
        }
    }
}
