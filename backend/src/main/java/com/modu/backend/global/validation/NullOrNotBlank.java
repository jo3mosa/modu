package com.modu.backend.global.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * null은 허용하되 빈 문자열/공백은 거부하는 커스텀 검증 어노테이션
 *
 * PATCH 요청처럼 null = "변경 없음", 빈 값 = 잘못된 입력을 구분해야 할 때 사용
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NullOrNotBlankValidator.class)
public @interface NullOrNotBlank {
    String message() default "값이 있는 경우 공백일 수 없습니다.";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
