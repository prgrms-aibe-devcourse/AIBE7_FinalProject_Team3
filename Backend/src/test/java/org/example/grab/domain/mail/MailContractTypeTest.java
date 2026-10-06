package org.example.grab.domain.mail;

import org.example.grab.domain.mail.dto.EmailMessage;
import org.example.grab.domain.mail.error.EmailSendException;
import org.example.grab.domain.mail.service.AsyncEmailDispatcher;
import org.example.grab.domain.mail.service.EmailSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/*
    호출하는 쪽이 쓰는 메일 발송 계약에 Spring Mail·Jakarta Mail 타입이 드러나지 않는지 확인한다(TECHSTACK.md 4.3).
    공개 생성자·메서드의 매개변수·반환·예외 타입과 상위 타입을 검사한다. 메일 타입은 SmtpEmailSender 안에만 둔다.
 */
class MailContractTypeTest {

    private static final List<String> MAIL_PACKAGES = List.of("org.springframework.mail", "jakarta.mail", "org.eclipse.angus");

    private static final List<Class<?>> CONTRACT_TYPES = List.of(
            EmailSender.class, EmailMessage.class, EmailSendException.class, AsyncEmailDispatcher.class);

    @Test
    @DisplayName("메일 발송 계약의 공개 시그니처에 Spring Mail·Jakarta Mail 타입이 없다")
    void exposesNoMailTypes() {
        List<String> violations = new ArrayList<>();
        for (Class<?> type : CONTRACT_TYPES) {
            for (Class<?> exposed : exposedTypes(type)) {
                if (isMailType(exposed)) {
                    violations.add(type.getSimpleName() + " -> " + exposed.getName());
                }
            }
        }

        assertThat(violations).isEmpty();
    }

    private static List<Class<?>> exposedTypes(Class<?> type) {
        List<Class<?>> exposed = new ArrayList<>();
        for (Class<?> superType = type.getSuperclass(); superType != null; superType = superType.getSuperclass()) {
            exposed.add(superType);
        }
        exposed.addAll(Arrays.asList(type.getInterfaces()));
        for (Constructor<?> constructor : type.getConstructors()) {
            exposed.addAll(Arrays.asList(constructor.getParameterTypes()));
            exposed.addAll(Arrays.asList(constructor.getExceptionTypes()));
        }
        for (Method method : type.getMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                exposed.add(method.getReturnType());
                exposed.addAll(Arrays.asList(method.getParameterTypes()));
                exposed.addAll(Arrays.asList(method.getExceptionTypes()));
            }
        }
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                exposed.add(component.getType());
            }
        }
        return exposed;
    }

    private static boolean isMailType(Class<?> type) {
        Class<?> target = type.isArray() ? type.getComponentType() : type;
        return MAIL_PACKAGES.stream().anyMatch(target.getName()::startsWith);
    }
}
