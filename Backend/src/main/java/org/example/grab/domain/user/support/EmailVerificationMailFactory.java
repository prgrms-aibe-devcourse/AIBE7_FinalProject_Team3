package org.example.grab.domain.user.support;

import org.example.grab.domain.mail.dto.EmailMessage;
import org.example.grab.domain.user.repository.EmailVerificationCodeRepository;
import org.springframework.stereotype.Component;

/*
    이메일 인증 코드 메일을 만든다(GR-61 M03-02, MAIL.md 6.4).
    - 제목에는 코드를 넣지 않는다. EmailMessage.toString()과 발송 실패 로그에 제목이 남기 때문이다
    - HTML·텍스트 본문에 코드와 유효 시간을 넣는다. 유효 시간은 저장 TTL과 같은 값을 써서 안내와 실제가 어긋나지 않게 한다
    템플릿 엔진 없이 코드 안의 문자열로 만든다(과설계 방지 기준). 본문에 넣는 값은 숫자 코드와 분 단위 숫자뿐이라 HTML 이스케이프가 필요 없다.
 */
@Component
public class EmailVerificationMailFactory {

    static final String SUBJECT = "[GRAB] 이메일 인증 코드 안내";

    private static final long VALID_MINUTES = EmailVerificationCodeRepository.CODE_TTL.toMinutes();

    private static final String TEXT_TEMPLATE = """
            GRAB 회원가입을 위한 이메일 인증 코드입니다.

            인증 코드: %s

            이 코드는 %d분 동안 유효합니다.
            본인이 요청하지 않았다면 이 메일을 무시해 주세요.
            """;

    private static final String HTML_TEMPLATE = """
            <!DOCTYPE html>
            <html lang="ko">
            <body>
            <p>GRAB 회원가입을 위한 이메일 인증 코드입니다.</p>
            <p style="font-size: 24px; font-weight: bold; letter-spacing: 4px;">%s</p>
            <p>이 코드는 %d분 동안 유효합니다.</p>
            <p>본인이 요청하지 않았다면 이 메일을 무시해 주세요.</p>
            </body>
            </html>
            """;

    // email은 정규화한 받는 주소, code는 숫자 6자리다
    public EmailMessage create(String email, String code) {
        return new EmailMessage(
                email,
                SUBJECT,
                HTML_TEMPLATE.formatted(code, VALID_MINUTES),
                TEXT_TEMPLATE.formatted(code, VALID_MINUTES)
        );
    }
}
