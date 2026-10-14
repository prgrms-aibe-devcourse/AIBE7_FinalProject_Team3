package org.example.grab.domain.seller.error;

import org.example.grab.domain.seller.validation.ValidBusinessRegistrationNumber;
import org.example.grab.global.error.ConstraintErrorCodeMapping;
import org.example.grab.global.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;
import java.util.Map;

@Component
public class SellerConstraintErrorCodeMapping implements ConstraintErrorCodeMapping {

    @Override
    public Map<Class<? extends Annotation>, ErrorCode> errorCodes() {
        return Map.of(ValidBusinessRegistrationNumber.class, SellerErrorCode.INVALID_BUSINESS_REGISTRATION_NUMBER);
    }
}
