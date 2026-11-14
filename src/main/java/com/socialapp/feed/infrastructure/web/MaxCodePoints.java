package com.socialapp.feed.infrastructure.web;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Like {@code @Size(max = ...)} but counts Unicode code points instead of UTF-16 units, so an emoji counts
 * as 1 character, the same way the domain limit ({@code Post.MAX_CONTENT_LENGTH}) and the frontend counter
 * ({@code [...text].length}) count. {@code @Size} would reject e.g. 300 emoji (600 UTF-16 units) that the
 * domain accepts. Null is valid (combine with {@code @NotBlank} where required).
 * Targets fields only, so on a record component it is validated exactly once.
 */
@Documented
@Constraint(validatedBy = MaxCodePoints.Validator.class)
@Target({ElementType.FIELD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxCodePoints {

    int value();

    String message() default "must be at most {value} characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<MaxCodePoints, CharSequence> {
        private int max;

        @Override
        public void initialize(MaxCodePoints annotation) {
            this.max = annotation.value();
        }

        @Override
        public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
            if (value == null) return true;
            String s = value.toString();
            return s.codePointCount(0, s.length()) <= max;
        }
    }
}
