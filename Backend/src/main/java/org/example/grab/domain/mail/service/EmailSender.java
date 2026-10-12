package org.example.grab.domain.mail.service;

import org.example.grab.domain.mail.dto.EmailMessage;

import java.util.List;

/*
    메일 발송 창구. 호출하는 쪽은 이 인터페이스와 EmailMessage에만 의존하고, SMTP 등 발송 방식은 구현체에 둔다(TECHSTACK.md 4.3).
    구현체는 grab.mail.provider 설정으로 선택하며, 완성된 메일을 동기로 보낸다. 재시도·요청 제한·비동기 실행은 호출하는 쪽의 책임이다.
    발송에 실패하면 EmailSendException을 던지므로 호출자는 실패를 확인할 수 있다.
 */
public interface EmailSender {

    void send(EmailMessage message);

    /*
        여러 통을 한 번에 보낸다(GR-69). 한 통씩 send()를 반복하면 구현체가 매번 연결·인증을 다시 하므로,
        수신자가 많은 알림에서는 이 메서드를 쓴다. 연결을 재사용할 수 있는 구현체는 재사용한다.
        한 통이 실패해도 나머지 발송을 멈추지 않고, 실패가 하나라도 있으면 마지막에 EmailSendException을 한 번 던진다.
        어떤 주소가 실패했는지는 예외에 담지 않는다. 받는 주소를 로그·예외 메시지에 남기지 않기 위함이다(NFR-011).
     */
    void sendAll(List<EmailMessage> messages);
}
