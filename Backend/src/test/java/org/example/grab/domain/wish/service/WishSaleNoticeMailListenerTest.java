package org.example.grab.domain.wish.service;

import org.example.grab.domain.drop.event.DropGrabStartedEvent;
import org.example.grab.domain.drop.event.DropSaleStartingSoonEvent;
import org.example.grab.domain.mail.dto.EmailMessage;
import org.example.grab.domain.mail.service.AsyncEmailDispatcher;
import org.example.grab.domain.wish.dto.WishMailRecipientProjection;
import org.example.grab.domain.wish.repository.WishRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class WishSaleNoticeMailListenerTest {

    private static final String RECIPIENT = "buyer@example.com";

    private final WishRepository wishRepository = mock(WishRepository.class);

    private final AsyncEmailDispatcher emailDispatcher = mock(AsyncEmailDispatcher.class);

    private final WishSaleNoticeMailListener listener =
            new WishSaleNoticeMailListener(wishRepository, emailDispatcher, "https://grab.example.com/");

    @Test
    @DisplayName("판매자가 입력한 DROP 이름의 HTML 특수문자를 이스케이프해 본문에 넣는다")
    // DROP 이름은 판매자 입력값이라 그대로 넣으면 메일 본문의 마크업이 깨지거나 주입될 수 있어 확인하는 테스트
    void escapesDropNameInHtmlBody() {
        // given
        when(wishRepository.findSaleStartMailRecipients(List.of(1L)))
                .thenReturn(List.of(recipient(1L, "<b>한정판</b> & 스니커즈", RECIPIENT)));

        // when
        listener.onDropGrabStarted(new DropGrabStartedEvent(List.of(1L)));

        // then
        ArgumentCaptor<List<EmailMessage>> captor = ArgumentCaptor.captor();
        verify(emailDispatcher).dispatchAll(captor.capture());
        EmailMessage message = captor.getValue().get(0);
        assertThat(message.to()).isEqualTo(RECIPIENT);
        assertThat(message.htmlBody()).contains("&lt;b&gt;한정판&lt;/b&gt; &amp; 스니커즈");
        assertThat(message.htmlBody()).doesNotContain("<b>한정판</b>");
        // 텍스트 본문은 마크업으로 해석되지 않으므로 원문을 그대로 쓴다
        assertThat(message.textBody()).contains("<b>한정판</b> & 스니커즈");
    }

    @Test
    @DisplayName("설정한 프런트엔드 주소로 DROP 상세 링크를 만들고 슬래시가 겹치지 않는다")
    void buildsDropDetailLink() {
        // given: 끝에 슬래시가 있는 설정값
        when(wishRepository.findSaleStartMailRecipients(List.of(7L)))
                .thenReturn(List.of(recipient(7L, "한정판 스니커즈", RECIPIENT)));

        // when
        listener.onDropGrabStarted(new DropGrabStartedEvent(List.of(7L)));

        // then
        ArgumentCaptor<List<EmailMessage>> captor = ArgumentCaptor.captor();
        verify(emailDispatcher).dispatchAll(captor.capture());
        EmailMessage message = captor.getValue().get(0);
        assertThat(message.htmlBody()).contains("href=\"https://grab.example.com/drops/7\"");
        assertThat(message.textBody()).contains("https://grab.example.com/drops/7");
    }

    @Test
    @DisplayName("판매 임박 이벤트는 임박 문구와 리드 타임이 들어간 메일을 만든다")
    // 두 알림이 제목·배지·본문 문구만 다르고 나머지를 공유하므로, 임박 쪽 문구가 제대로 붙는지 확인하는 테스트
    void buildsStartingSoonMessage() {
        // given
        when(wishRepository.findSaleStartMailRecipients(List.of(3L)))
                .thenReturn(List.of(recipient(3L, "한정판 스니커즈", RECIPIENT)));

        // when
        listener.onDropSaleStartingSoon(new DropSaleStartingSoonEvent(List.of(3L)));

        // then
        ArgumentCaptor<List<EmailMessage>> captor = ArgumentCaptor.captor();
        verify(emailDispatcher).dispatchAll(captor.capture());
        EmailMessage message = captor.getValue().get(0);
        assertThat(message.subject()).isEqualTo("[GRAB] 한정판 스니커즈 판매가 곧 시작됩니다");
        assertThat(message.htmlBody()).contains("판매 임박", DropSaleStartingSoonEvent.LEAD.toMinutes() + "분 뒤 시작됩니다");
        assertThat(message.textBody()).contains("판매가 곧 시작됩니다", "상품 보러 가기");
        // 판매 시작 알림과 같은 레이아웃·링크를 쓴다
        assertThat(message.htmlBody()).contains("href=\"https://grab.example.com/drops/3\"");
    }

    @Test
    @DisplayName("수신자가 없으면 발송 창구를 호출하지 않는다")
    void skipsDispatchWithoutRecipients() {
        // given
        when(wishRepository.findSaleStartMailRecipients(anyList())).thenReturn(List.of());

        // when
        listener.onDropGrabStarted(new DropGrabStartedEvent(List.of(1L, 2L)));

        // then
        verifyNoInteractions(emailDispatcher);
    }

    @Test
    @DisplayName("수신자 조회가 실패해도 예외를 호출한 쪽에 던지지 않고 경고 로그만 남긴다")
    // 커밋 뒤 호출이라 예외가 밖으로 새면 전환 배치 루프가 끊긴다(GR-69)
    void logsAndSwallowsQueryFailure(CapturedOutput output) {
        // given
        when(wishRepository.findSaleStartMailRecipients(anyList()))
                .thenThrow(new QueryTimeoutException("조회 시간 초과"));

        // when & then
        assertThatCode(() -> listener.onDropGrabStarted(new DropGrabStartedEvent(List.of(1L))))
                .doesNotThrowAnyException();
        verifyNoInteractions(emailDispatcher);
        assertThat(output).contains("WARN", "판매 시작 메일 준비 실패");
    }

    @Test
    @DisplayName("로그에 받는 주소와 본문 원문을 남기지 않는다")
    // NFR-011과 같은 방침. 수신자가 많아도 로그에는 건수만 남는지 확인하는 테스트
    void doesNotLogRecipientOrBody(CapturedOutput output) {
        // given
        when(wishRepository.findSaleStartMailRecipients(List.of(1L)))
                .thenReturn(List.of(recipient(1L, "한정판 스니커즈", RECIPIENT)));

        // when
        listener.onDropGrabStarted(new DropGrabStartedEvent(List.of(1L)));

        // then
        assertThat(output).contains("판매 시작 메일 발송 요청", "수신자 1명");
        assertThat(output).doesNotContain(RECIPIENT);
    }

    private static WishMailRecipientProjection recipient(Long dropId, String dropName, String email) {
        return new Recipient(dropId, dropName, email);
    }

    // 프로젝션을 mock으로 만들면 when(...) 안에서 다시 스터빙하게 되므로 간단한 구현을 쓴다
    private record Recipient(Long dropId, String dropName, String email) implements WishMailRecipientProjection {

        @Override
        public Long getDropId() {
            return dropId;
        }

        @Override
        public String getDropName() {
            return dropName;
        }

        @Override
        public String getEmail() {
            return email;
        }
    }
}
