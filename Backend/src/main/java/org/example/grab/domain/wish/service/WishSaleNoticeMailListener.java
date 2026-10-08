package org.example.grab.domain.wish.service;

import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.drop.event.DropGrabStartedEvent;
import org.example.grab.domain.drop.event.DropSaleStartingSoonEvent;
import org.example.grab.domain.mail.dto.EmailMessage;
import org.example.grab.domain.mail.service.AsyncEmailDispatcher;
import org.example.grab.domain.wish.WishNotice;
import org.example.grab.domain.wish.dto.WishMailRecipientProjection;
import org.example.grab.domain.wish.repository.WishRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * WISH한 DROP의 판매 시작이 임박하거나 시작되면 활성 WISH 사용자에게 메일을 보낸다(GR-69).
 * 두 알림은 문구만 다르고 수신자 조회·본문 작성·발송 경로를 공유한다.
 * 수신자 조회와 본문 작성은 WISH 도메인에 두고, 발송은 AsyncEmailDispatcher에만 맡긴다.
 * 덕분에 DROP·주문 서비스에 메일 구현이 들어가지 않고, SMTP 구현이 바뀌어도 호출하는 쪽은 그대로다.
 */
@Slf4j
@Component
public class WishSaleNoticeMailListener {

    private static final String DETAIL_PATH_FORMAT = "/drops/%d";

    // 메시지당 BCC 수신자 상한. Gmail SMTP 기준 100명이고 Brevo 상한은 공개되지 않아 보수적으로 맞춘다
    private static final int MAX_BCC_PER_MAIL = 100;

    /*
     * 받는 사람이 알림을 멈출 방법을 메일 안에서 찾을 수 있어야 한다. 지금 수신 중단 수단은 WISH 취소뿐이므로 그것을 안내한다.
     * 개인별 수신 거부 링크는 수신자마다 다른 토큰이 필요해 BCC 묶음과 함께 쓸 수 없다. opt-out 이슈에서 발송 방식과 같이 정한다.
     */
    private static final String FOOTER = "이 메일은 WISH한 DROP의 판매 소식을 알리기 위해 발송됐습니다."
            + " 알림을 받지 않으려면 해당 DROP의 WISH를 취소하세요.";

    /*
     * 메일 클라이언트는 외부 CSS·웹폰트를 불러오지 않고 flex·grid도 지원이 고르지 않아,
     * 레이아웃은 table로 짜고 스타일은 모두 인라인으로 둔다. 색·반지름·굵기는 프런트엔드
     * global.css의 토큰(lime #bdff28, ink #1b1e17, muted #74786d, line #e5e8df, soft #f5f7f1)과 맞춘다.
     * 서식 인자는 순서대로 배지, DROP 이름(이스케이프됨), 안내 문구, 링크, 버튼 문구, WISH 안내, 푸터다.
     */
    private static final String HTML_TEMPLATE = """
            <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="width:100%%;background:#f5f7f1;padding:32px 12px;">
              <tr><td align="center">
                <table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0" style="width:600px;max-width:100%%;background:#ffffff;border:1px solid #e5e8df;border-radius:16px;">
                  <tr><td style="padding:28px 32px 0;font:800 24px/1 'Manrope','DM Sans','Noto Sans KR','Apple SD Gothic Neo','Malgun Gothic',Arial,sans-serif;letter-spacing:-1.5px;color:#1b1e17;">GRAB</td></tr>
                  <tr><td style="padding:22px 32px 0;">
                    <span style="display:inline-block;padding:6px 10px;border-radius:5px;background:#bdff28;font:800 10px/1 'DM Sans','Noto Sans KR','Apple SD Gothic Neo','Malgun Gothic',Arial,sans-serif;letter-spacing:0.5px;color:#1b1e17;">%s</span>
                  </td></tr>
                  <tr><td style="padding:14px 32px 0;font:800 26px/1.35 'DM Sans','Noto Sans KR','Apple SD Gothic Neo','Malgun Gothic',Arial,sans-serif;letter-spacing:-0.5px;color:#1b1e17;">%s</td></tr>
                  <tr><td style="padding:12px 32px 0;font:400 14px/1.7 'DM Sans','Noto Sans KR','Apple SD Gothic Neo','Malgun Gothic',Arial,sans-serif;color:#74786d;">%s</td></tr>
                  <tr><td style="padding:24px 32px 0;">
                    <a href="%s" style="display:inline-block;padding:15px 22px;border-radius:9px;background:#1b1e17;color:#ffffff;font:800 13px/1 'DM Sans','Noto Sans KR','Apple SD Gothic Neo','Malgun Gothic',Arial,sans-serif;text-decoration:none;">%s</a>
                  </td></tr>
                  <tr><td style="padding:26px 32px 0;"><div style="height:1px;background:#e5e8df;font-size:0;line-height:0;">&nbsp;</div></td></tr>
                  <tr><td style="padding:16px 32px 28px;font:400 12px/1.6 'DM Sans','Noto Sans KR','Apple SD Gothic Neo','Malgun Gothic',Arial,sans-serif;color:#74786d;">%s</td></tr>
                </table>
                <div style="padding:16px 8px 0;font:400 11px/1.6 'DM Sans','Noto Sans KR','Apple SD Gothic Neo','Malgun Gothic',Arial,sans-serif;color:#92958c;">%s</div>
              </td></tr>
            </table>
            """;

    /** 서식 인자는 순서대로 제목, 안내 문구, 버튼 문구, 링크, WISH 안내, 푸터다. */
    private static final String TEXT_TEMPLATE = """
            %s

            %s
            %s: %s

            %s

            %s
            """;

    /** 알림 종류별 문구. 레이아웃과 발송 경로는 공유하고 이 값만 달라진다. */
    private enum Notice {

        STARTING_SOON(
                "[GRAB] %s 판매가 곧 시작됩니다",
                "판매 임박",
                "WISH한 DROP의 판매가 " + DropSaleStartingSoonEvent.LEAD.toMinutes()
                        + "분 뒤 시작됩니다. 한정 수량이라 조기 품절될 수 있습니다.",
                "상품 보러 가기"),

        STARTED(
                "[GRAB] %s 판매가 시작됐습니다",
                "판매 시작",
                "WISH한 DROP의 판매가 방금 시작됐습니다. 한정 수량이라 조기 품절될 수 있습니다.",
                "지금 구매하러 가기");

        private final String subjectFormat;
        private final String badge;
        private final String lead;
        private final String cta;

        Notice(String subjectFormat, String badge, String lead, String cta) {
            this.subjectFormat = subjectFormat;
            this.badge = badge;
            this.lead = lead;
            this.cta = cta;
        }
    }

    private final WishRepository wishRepository;
    private final AsyncEmailDispatcher emailDispatcher;
    private final String webBaseUrl;

    public WishSaleNoticeMailListener(
            WishRepository wishRepository,
            AsyncEmailDispatcher emailDispatcher,
            @Value("${grab.web.base-url}") String webBaseUrl
    ) {
        this.wishRepository = wishRepository;
        this.emailDispatcher = emailDispatcher;
        // 설정값 끝에 슬래시가 있어도 링크가 //drops/1이 되지 않게 떼어 둔다
        this.webBaseUrl = webBaseUrl.endsWith("/") ? webBaseUrl.substring(0, webBaseUrl.length() - 1) : webBaseUrl;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDropSaleStartingSoon(DropSaleStartingSoonEvent event) {
        dispatch(event.dropIds(), Notice.STARTING_SOON);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDropGrabStarted(DropGrabStartedEvent event) {
        dispatch(event.dropIds(), Notice.STARTED);
    }

    /*
     * 선점·전환 트랜잭션이 커밋된 뒤에만 발송한다. 롤백되면 이 리스너는 호출되지 않는다.
     * 조회는 Spring Data가 쿼리마다 여는 트랜잭션으로 충분해 별도 트랜잭션을 열지 않는다.
     * 예외를 밖으로 던지면 커밋 호출자(전환 배치 루프)로 전파돼 남은 배치가 한 주기 밀리므로 여기서 끝낸다.
     * 배치는 이미 커밋됐고 메일은 DROP 상태의 정확성에 영향을 주지 않는다(AsyncEmailDispatcher 계약).
     */
    private void dispatch(List<Long> dropIds, Notice notice) {
        try {
            List<WishMailRecipientProjection> recipients = wishRepository.findSaleStartMailRecipients(dropIds);
            if (recipients.isEmpty()) {
                return;
            }
            List<EmailMessage> messages = toBccMessages(recipients, notice);
            // 받는 주소·본문은 남기지 않고 건수만 남긴다(NFR-011과 같은 방침)
            log.info("{} 메일 발송 요청: DROP {}건, 수신자 {}명, 메일 {}통",
                    notice.badge, dropIds.size(), recipients.size(), messages.size());
            emailDispatcher.dispatchAll(messages);
        } catch (RuntimeException e) {
            log.warn("{} 메일 준비 실패: DROP {}건", notice.badge, dropIds.size(), e);
        }
    }

    /*
     * 같은 DROP의 수신자는 제목·본문이 완전히 같으므로 한 통에 BCC로 묶는다(GR-69).
     * 통마다 메일을 만들면 수신자당 MAIL FROM·RCPT·DATA·본문을 모두 반복하지만, 묶으면 수신자당 RCPT 한 번으로 끝난다.
     * Brevo 실측으로 통당 0.643초가 수신자당 0.134초가 됐다. 판매 시작 알림은 전원 도착까지의 시간이 요구사항이다.
     * 조회가 ORDER BY drop_id, id로 정렬돼 오므로 앞에서부터 끊어 담으면 DROP 경계가 자연히 맞는다.
     */
    private List<EmailMessage> toBccMessages(List<WishMailRecipientProjection> recipients, Notice notice) {
        List<EmailMessage> messages = new ArrayList<>();
        int start = 0;
        while (start < recipients.size()) {
            WishMailRecipientProjection head = recipients.get(start);
            int end = start + 1;
            while (end < recipients.size()
                    && end - start < MAX_BCC_PER_MAIL
                    && head.getDropId().equals(recipients.get(end).getDropId())) {
                end++;
            }
            List<String> bcc = recipients.subList(start, end).stream()
                    .map(WishMailRecipientProjection::getEmail)
                    .toList();
            messages.add(toMessage(head, bcc, notice));
            start = end;
        }
        return messages;
    }

    private EmailMessage toMessage(WishMailRecipientProjection drop, List<String> bcc, Notice notice) {
        String dropName = drop.getDropName();
        String link = webBaseUrl + DETAIL_PATH_FORMAT.formatted(drop.getDropId());
        // DROP 이름은 판매자가 입력한 값이라 HTML 본문에 넣기 전에 이스케이프한다
        String htmlBody = HTML_TEMPLATE.formatted(
                notice.badge, HtmlUtils.htmlEscape(dropName), notice.lead, link, notice.cta,
                WishNotice.MESSAGE, FOOTER);
        // 텍스트 본문은 마크업으로 해석되지 않으므로 이름을 그대로 쓴다
        String textBody = TEXT_TEMPLATE.formatted(
                notice.subjectFormat.formatted(dropName), notice.lead, notice.cta, link, WishNotice.MESSAGE, FOOTER);
        return EmailMessage.toBcc(bcc, notice.subjectFormat.formatted(dropName), htmlBody, textBody);
    }
}
