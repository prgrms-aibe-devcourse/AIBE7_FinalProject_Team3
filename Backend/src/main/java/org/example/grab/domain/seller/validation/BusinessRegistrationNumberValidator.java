package org.example.grab.domain.seller.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

public class BusinessRegistrationNumberValidator implements ConstraintValidator<ValidBusinessRegistrationNumber, String> {

    private static final Pattern TEN_DIGITS = Pattern.compile("\\d{10}");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // 필수값 위반은 @NotEmpty가 보고한다
        if (value == null || value.isEmpty()) {
            return true;
        }
        return TEN_DIGITS.matcher(value).matches();
    }
}
