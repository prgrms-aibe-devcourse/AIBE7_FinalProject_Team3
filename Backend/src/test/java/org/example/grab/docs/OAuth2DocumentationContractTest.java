package org.example.grab.docs;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth2DocumentationContractTest {

    private static final Path DEVELOPMENT_DOCS = Path.of("docs", "development");
    private static final Pattern MEMBER_SECTION_NUMBER = Pattern.compile("(?m)^### 1\\.(\\d+)\\s");
    private static final Pattern JSON_FIELD = Pattern.compile("\"([A-Za-z][A-Za-z0-9]*)\"\\s*:");
    private static final Pattern SENSITIVE_REDIRECT_PARAMETER = Pattern.compile(
            "(?i)Location:.*[?&](code|token|access_token|refresh_token|email)="
    );

    private static String memberAuth;
    private static String requirements;
    private static String erd;

    @BeforeAll
    static void readChangedDocuments() throws IOException {
        memberAuth = Files.readString(DEVELOPMENT_DOCS.resolve("api-spec/MEMBER_AUTH.md"));
        requirements = Files.readString(DEVELOPMENT_DOCS.resolve("REQUIREMENTS.md"));
        erd = Files.readString(DEVELOPMENT_DOCS.resolve("ERD.md"));
    }

    @Test
    @DisplayName("회원 인증 API 절 번호는 중복이나 누락 없이 순서대로 이어진다")
    void memberAuthSectionsRemainSequential() {
        // given
        Matcher matcher = MEMBER_SECTION_NUMBER.matcher(memberAuth);
        List<Integer> sectionNumbers = new ArrayList<>();

        // when
        while (matcher.find()) {
            sectionNumbers.add(Integer.parseInt(matcher.group(1)));
        }

        // then
        assertThat(sectionNumbers).containsExactlyElementsOf(
                java.util.stream.IntStream.rangeClosed(1, 13).boxed().toList()
        );
    }

    @Test
    @DisplayName("소셜 로그인 시작 계약은 지원 제공자와 비회원 접근 및 state 검증을 명시한다")
    void socialLoginStartDocumentsProviderBoundaryAndState() {
        // given
        String section = section(memberAuth, "1.4 KAKAO·GOOGLE 소셜 로그인 시작");

        // when & then
        assertThat(section)
                .contains("GET /api/v1/auth/oauth2/{provider}")
                .contains("**인증**: 불필요")
                .contains("`provider`는 `kakao` 또는 `google`")
                .contains("**응답:** `302 Found`")
                .contains("`state`")
                .contains("요청에서 임의의 반환 URL을 받지 않는다")
                .contains("`UNSUPPORTED_OAUTH2_PROVIDER`");
    }

    @Test
    @DisplayName("소셜 로그인 콜백은 기존 회원, 최초 회원가입, 계정 충돌을 안전하게 구분한다")
    void socialLoginCallbackDocumentsEveryOutcomeWithoutLeakingSecrets() {
        // given
        String section = section(memberAuth, "1.5 소셜 로그인 콜백");

        // when
        List<String> redirectHeaders = section.lines()
                .filter(line -> line.startsWith("Location:"))
                .toList();

        // then
        assertThat(section)
                .contains("`state`를 검증")
                .contains("?result=success")
                .contains("oauth2_signup_token=<one-time-token>")
                .contains("?result=signup_required")
                .contains("?error=<error-code>")
                .contains("`ACCOUNT_LINK_REQUIRED`")
                .contains("`OAUTH2_EMAIL_REQUIRED`");
        assertThat(redirectHeaders).hasSize(3);
        assertThat(redirectHeaders)
                .allSatisfy(header -> assertThat(SENSITIVE_REDIRECT_PARAMETER.matcher(header).find())
                        .as("민감정보가 리다이렉트 쿼리 파라미터에 포함되지 않음: %s", header)
                        .isFalse());
    }

    @Test
    @DisplayName("소셜 회원가입 요청은 표시 이름만 받고 일회용 컨텍스트의 만료와 재사용을 거부한다")
    void socialSignupAcceptsOnlyDisplayNameAndRejectsInvalidContexts() {
        // given
        String section = section(memberAuth, "1.6 소셜 회원가입 완료");

        // when
        Set<String> requestFields = jsonFields(jsonCodeBlockAfter(section, "**요청:**"));

        // then
        assertThat(requestFields).containsExactly("displayName");
        assertThat(section)
                .contains("`oauth2_signup_token` HttpOnly 쿠키")
                .contains("`X-XSRF-TOKEN` 헤더 필요")
                .contains("클라이언트가 제공자, 이메일 또는 휴대폰 번호를 지정할 수 없다")
                .contains("성공 시 한 번만 소비")
                .contains("만료·재사용된 토큰은 거부")
                .contains("휴대폰 번호와 `password_hash`를 저장하지 않는다")
                .contains("`OAUTH2_SIGNUP_CONTEXT_INVALID`")
                .contains("`OAUTH2_SIGNUP_CONTEXT_EXPIRED`")
                .contains("`DUPLICATE_EMAIL`")
                .contains("`INVALID_DISPLAY_NAME`");
    }

    @Test
    @DisplayName("비밀번호 변경 계약은 LOCAL 회원으로 제한하고 소셜 회원 거부 오류를 명시한다")
    void passwordChangeRemainsLocalOnly() {
        // given
        String section = section(memberAuth, "1.11 비밀번호 변경");

        // when & then
        assertThat(section)
                .contains("**인증**: 필요 (`USER`, `LOCAL` 회원)")
                .contains("`PASSWORD_NOT_AVAILABLE`")
                .contains("`KAKAO`·`GOOGLE` 회원은 로컬 비밀번호가 없으므로 이 API를 사용할 수 없다");
    }

    @Test
    @DisplayName("소셜 인증 요구사항은 중복 방지, 선택 입력, 계정 비병합, 비밀정보 보호를 포함한다")
    void socialAuthRequirementsCoverIdentityAndSecurityBoundaries() {
        // given
        String auth006 = requirementRow("AUTH-006");
        String auth007 = requirementRow("AUTH-007");
        String auth008 = requirementRow("AUTH-008");
        String nfr010 = requirementRow("NFR-010");

        // when & then
        assertThat(auth006)
                .contains("KAKAO 또는 GOOGLE")
                .contains("고유 사용자 식별자")
                .contains("회원이 중복 생성되지 않는다");
        assertThat(auth007)
                .contains("휴대폰 번호를 요구하지 않아야 한다")
                .contains("휴대폰 번호와 비밀번호 해시는 저장하지 않는다");
        assertThat(auth008)
                .contains("계정을 자동으로 병합하지 않아야 한다")
                .contains("기존 계정과 데이터는 변경되지 않으며");
        assertThat(nfr010)
                .contains("OAuth2 state를 검증")
                .contains("Client Secret은 저장소·로그에")
                .contains("인가 코드·토큰·제공자 개인정보는 프론트엔드 리다이렉트 URL·응답 본문·로그에 노출하지 않는다");
    }

    @Test
    @DisplayName("회원 ERD는 소셜 회원의 선택 전화번호와 조건부 비밀번호를 표현한다")
    void userErdSupportsSocialMembersWithoutLocalCredentials() {
        // given
        List<String> phone = erdRow("phone");
        List<String> passwordHash = erdRow("password_hash");
        List<String> provider = erdRow("provider");

        // when & then
        assertThat(phone).containsExactly("`phone`", "VARCHAR(30)", "X", "회원 연락처");
        assertThat(passwordHash)
                .containsExactly("`password_hash`", "VARCHAR(255)", "조건부", "LOCAL 회원만 필수, Argon2id 해시 저장");
        assertThat(provider.get(3)).contains("`LOCAL`, `KAKAO`, `GOOGLE`");
        assertThat(erd).contains("소셜 로그인 시 휴대폰 번호를 받지 않는다");
    }

    private static String section(String document, String heading) {
        String marker = "### " + heading;
        int start = document.indexOf(marker);
        assertThat(start).as("문서 절 '%s'", heading).isNotNegative();

        int end = document.indexOf("\n### ", start + marker.length());
        return end < 0 ? document.substring(start) : document.substring(start, end);
    }

    private static String jsonCodeBlockAfter(String section, String marker) {
        int markerStart = section.indexOf(marker);
        assertThat(markerStart).as("JSON 기준 표식 '%s'", marker).isNotNegative();

        int fenceStart = section.indexOf("```json", markerStart + marker.length());
        assertThat(fenceStart).as("'%s' 뒤 JSON 코드 블록", marker).isNotNegative();

        int contentStart = section.indexOf('\n', fenceStart) + 1;
        int fenceEnd = section.indexOf("```", contentStart);
        assertThat(fenceEnd).as("JSON 코드 블록 닫힘").isNotNegative();
        return section.substring(contentStart, fenceEnd);
    }

    private static Set<String> jsonFields(String json) {
        Matcher matcher = JSON_FIELD.matcher(json);
        Set<String> fields = new LinkedHashSet<>();
        while (matcher.find()) {
            fields.add(matcher.group(1));
        }
        return fields;
    }

    private static String requirementRow(String id) {
        List<String> rows = requirements.lines()
                .filter(line -> line.startsWith("| " + id + " |"))
                .toList();
        assertThat(rows).as("요구사항 ID '%s'의 유일한 행", id).hasSize(1);
        return rows.get(0);
    }

    private static List<String> erdRow(String column) {
        String prefix = "`" + column + "`";
        String row = erd.lines()
                .filter(line -> line.startsWith("|") && line.contains(prefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ERD 컬럼을 찾을 수 없음: " + column));

        return Pattern.compile("\\|")
                .splitAsStream(row)
                .map(String::trim)
                .filter(cell -> !cell.isEmpty())
                .toList();
    }
}
