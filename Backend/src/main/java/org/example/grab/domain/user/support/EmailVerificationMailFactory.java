package org.example.grab.domain.user.support;

import org.example.grab.domain.mail.dto.EmailMessage;
import org.example.grab.domain.user.repository.EmailVerificationCodeRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

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
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>GRAB 이메일 인증</title>
            </head>
            <body style="margin:0;padding:0;background-color:#fafaf7;color:#191b17;font-family:'DM Sans','Noto Sans KR','Apple SD Gothic Neo','Malgun Gothic',Arial,sans-serif;">
              <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="width:100%%;background-color:#fafaf7;">
                <tr><td align="center" style="padding:32px 12px;">
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="width:100%%;max-width:560px;background-color:#ffffff;border:1px solid #dedfd8;border-radius:8px;">
                    <tr><td style="height:6px;background-color:#bdff28;font-size:0;line-height:0;">&nbsp;</td></tr>
                    <tr><td bgcolor="#ffffff" style="padding:24px;border-bottom:1px solid #dedfd8;background-color:#ffffff;">
                      <img src="cid:grab-logo" alt="GRAB" width="144" height="48" style="display:block;width:144px;height:48px;border:0;">
                    </td></tr>
                    <tr><td style="padding:32px 24px;">
                      <h1 style="margin:0;color:#191b17;font-size:26px;font-weight:800;line-height:1.4;letter-spacing:-1px;">이메일 인증</h1>
                      <p style="margin:12px 0 24px;color:#64685e;font-size:14px;line-height:1.8;">GRAB 회원가입을 위한 이메일 인증 코드입니다.<br>가입 화면에 아래 6자리 코드를 입력해주세요.</p>
                      <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="width:100%%;background-color:#f1f2eb;border:1px solid #dedfd8;border-radius:4px;">
                        <tr><td align="center" style="padding:24px 16px;">
                          <p style="margin:0;color:#64685e;font-size:12px;font-weight:700;line-height:1.5;">인증 코드</p>
                          <p style="margin:10px 0 0;color:#191b17;font-family:'Courier New',monospace;font-size:36px;font-weight:700;line-height:1.3;letter-spacing:6px;">%s</p>
                        </td></tr>
                      </table>
                      <p style="margin:16px 0 0;color:#64685e;font-size:13px;line-height:1.7;text-align:center;">이 코드는 <strong style="color:#191b17;">%d분 동안 유효</strong>합니다.</p>
                    </td></tr>
                    <tr><td style="padding:20px 24px;border-top:1px solid #dedfd8;">
                      <p style="margin:0;color:#64685e;font-size:12px;line-height:1.8;">본인이 요청하지 않았다면 이 메일을 무시해 주세요.</p>
                    </td></tr>
                  </table>
                  <p style="margin:20px 0 0;color:#64685e;font-size:11px;line-height:1.8;">좋아하는 상품을 WISH하고,<br>한정된 순간에 GRAB하세요.</p>
                </td></tr>
              </table>
            </body>
            </html>
            """;

    // email은 정규화한 받는 주소, code는 숫자 6자리다
    public EmailMessage create(String email, String code) {
        return new EmailMessage(
                email,
                List.of(),
                SUBJECT,
                HTML_TEMPLATE.formatted(code, VALID_MINUTES),
                TEXT_TEMPLATE.formatted(code, VALID_MINUTES),
                Map.of("grab-logo", "mail/grab-wordmark.png")
        );
    }
}
