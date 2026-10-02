package org.example.grab.domain.mail.error;

/*
    메일 발송 인프라 실패. HTTP 응답으로 바로 내보내는 비즈니스 오류가 아니므로 BusinessException·ErrorCode를 쓰지 않는다.
    호출하는 쪽이 Spring Mail 예외 타입에 의존하지 않도록 구현체가 발송 실패를 이 예외로 감싸 던진다.
    메시지에는 받는 주소·본문을 넣지 않는다. 원인 예외는 장애 분석을 위해 cause로 보존한다.
 */
public class EmailSendException extends RuntimeException {

    public EmailSendException(Throwable cause) {
        super("메일 발송에 실패했습니다.", cause);
    }
}
