package org.example.grab.domain.mail.service;

import org.example.grab.domain.mail.dto.EmailMessage;

/*
    메일 발송 창구. 호출하는 쪽은 이 인터페이스와 EmailMessage에만 의존하고, SMTP 등 발송 방식은 구현체에 둔다(TECHSTACK.md 4.3).
    구현체는 grab.mail.provider 설정으로 선택하며, 완성된 메일 한 통을 동기로 보낸다. 재시도·요청 제한·비동기 실행은 호출하는 쪽의 책임이다.
    발송에 실패하면 EmailSendException을 던지므로 호출자는 실패를 확인할 수 있다.
 */
public interface EmailSender {

    void send(EmailMessage message);
}
