package org.example.grab.domain.mail.service;

import org.example.grab.domain.mail.dto.EmailMessage;
import org.example.grab.domain.mail.error.EmailSendException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/*
    SMTP 서버가 응답하지 않을 때 발송이 무한 대기하지 않고 application-mail.yml의 타임아웃으로 끝나는지 확인한다.
    타임아웃 값은 테스트에서 따로 지정하지 않고 실제 설정 파일을 불러와 쓴다. 접속 주소만 테스트 서버로 바꾼다.
 */
class SmtpEmailSenderTimeoutTest {

    // application-mail.yml의 연결·읽기·쓰기 타임아웃
    private static final long TIMEOUT_MILLIS = 5_000;
    // 설정한 타임아웃보다 일찍 끝나면 다른 원인으로 실패한 것이므로 하한을 둔다
    private static final long MIN_ELAPSED_MILLIS = TIMEOUT_MILLIS - 500;
    // 타임아웃이 빠졌을 때 테스트가 멈추지 않도록 강제로 끊는 시간
    private static final Duration GUARD = Duration.ofSeconds(30);

    private final List<AutoCloseable> resources = new ArrayList<>();

    @AfterEach
    void closeResources() throws Exception {
        for (AutoCloseable resource : resources) {
            resource.close();
        }
    }

    @Test
    @DisplayName("설정 파일의 연결·읽기·쓰기 타임아웃이 JavaMailSender에 5초로 적용된다")
    void appliesConfiguredTimeouts() {
        contextRunner(1025).run(context -> {
            JavaMailSenderImpl mailSender = context.getBean(JavaMailSenderImpl.class);
            assertThat(mailSender.getJavaMailProperties())
                    .containsEntry("mail.smtp.connectiontimeout", "5000")
                    .containsEntry("mail.smtp.timeout", "5000")
                    .containsEntry("mail.smtp.writetimeout", "5000");
        });
    }

    @Test
    @DisplayName("연결은 받았지만 응답하지 않는 SMTP 서버면 읽기 타임아웃으로 끝나고 EmailSendException을 던진다")
    void failsWithReadTimeoutWhenServerNeverResponds() throws Exception {
        // given: 접속은 받지만 인사말(220)을 보내지 않는 서버
        ServerSocket server = register(new ServerSocket(0));
        Thread acceptor = startDaemon(() -> holdConnections(server));
        resources.add(acceptor::interrupt);

        // when & then
        Failure failure = sendAndMeasure(server.getLocalPort());
        assertThat(failure.elapsedMillis()).isBetween(MIN_ELAPSED_MILLIS, TIMEOUT_MILLIS + 3_000);
        assertThat(failure.exception())
                .hasCauseInstanceOf(MailSendException.class)
                .hasRootCauseInstanceOf(SocketTimeoutException.class)
                .hasRootCauseMessage("Read timed out");
    }

    @Test
    @DisplayName("본문을 받다가 멈춘 SMTP 서버면 쓰기 타임아웃으로 끝나고 EmailSendException을 던진다")
    void failsWithWriteTimeoutWhenServerStopsReading() throws Exception {
        // given: DATA까지 정상 응답한 뒤 본문을 읽지 않는 서버. 수신 버퍼를 작게 해 클라이언트 쓰기가 빨리 막히게 한다
        ServerSocket server = new ServerSocket();
        server.setReceiveBufferSize(4 * 1024);
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        register(server);
        Thread acceptor = startDaemon(() -> answerUntilData(server));
        resources.add(acceptor::interrupt);

        // when & then: 소켓 버퍼에 다 들어가지 않을 만큼 큰 본문을 보낸다
        String largeBody = "a".repeat(8 * 1024 * 1024);
        Failure failure = sendAndMeasure(server.getLocalPort(), largeBody);
        assertThat(failure.elapsedMillis()).isBetween(MIN_ELAPSED_MILLIS, TIMEOUT_MILLIS + 10_000);
        // 서버 연결 후 메시지 전송 중 실패는 cause가 아니라 MailSendException의 메시지별 예외에 담긴다
        // 쓰기 타임아웃이 나면 Angus Mail이 소켓을 닫으므로 가장 안쪽 원인은 SocketException(Socket closed)이다
        assertThat(failure.exception()).hasCauseInstanceOf(MailSendException.class);
        MailSendException cause = (MailSendException) failure.exception().getCause();
        assertThat(cause.getMessageExceptions()).singleElement()
                .satisfies(e -> assertThat(e.getCause()).isInstanceOf(IOException.class).hasMessage("Write timed out"));
    }

    private Failure sendAndMeasure(int port) {
        return sendAndMeasure(port, "인증 코드: 482913");
    }

    private Failure sendAndMeasure(int port, String body) {
        EmailMessage message = new EmailMessage("user@example.com", "[GRAB] 이메일 인증 코드", body, body);
        Failure[] result = new Failure[1];
        contextRunner(port).run(context -> {
            EmailSender sender = context.getBean(EmailSender.class);
            result[0] = assertTimeoutPreemptively(GUARD, () -> {
                long start = System.nanoTime();
                EmailSendException exception = catchThrowableOfType(EmailSendException.class, () -> sender.send(message));
                long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
                return new Failure(exception, elapsedMillis);
            });
        });
        assertThat(result[0].exception()).as("EmailSendException 발생").isNotNull();
        return result[0];
    }

    private static Thread startDaemon(Runnable task) {
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private ServerSocket register(ServerSocket server) {
        resources.add(server);
        return server;
    }

    private void holdConnections(ServerSocket server) {
        try {
            while (!server.isClosed()) {
                resources.add(server.accept());
            }
        } catch (Exception ignored) {
            // 테스트가 끝나 서버를 닫으면 accept가 예외로 빠져나온다
        }
    }

    // EHLO·MAIL·RCPT에 성공 응답, DATA에 354를 보낸 뒤 더는 읽지 않는다
    private void answerUntilData(ServerSocket server) {
        try {
            Socket socket = server.accept();
            resources.add(socket);
            OutputStream out = socket.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            reply(out, "220 silent ESMTP");
            String line;
            while ((line = in.readLine()) != null) {
                String command = line.toUpperCase();
                if (command.startsWith("DATA")) {
                    reply(out, "354 send data");
                    Thread.sleep(Long.MAX_VALUE);
                }
                reply(out, "250 ok");
            }
        } catch (Exception ignored) {
            // 테스트가 끝나 소켓을 닫거나 스레드를 중단하면 빠져나온다
        }
    }

    private static void reply(OutputStream out, String line) throws Exception {
        out.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static ApplicationContextRunner contextRunner(int port) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .withUserConfiguration(SmtpEmailSender.class)
                .withPropertyValues("spring.mail.host=127.0.0.1", "spring.mail.port=" + port);
    }

    private record Failure(EmailSendException exception, long elapsedMillis) {
    }
}
