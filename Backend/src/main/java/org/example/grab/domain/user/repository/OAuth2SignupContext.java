package org.example.grab.domain.user.repository;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.example.grab.domain.user.entity.AuthProvider;

/*
    소셜 가입 컨텍스트 값(MEMBER_AUTH 1.5, GR-46 M00-06). 콜백에서 확인한 제공자·고유 사용자 식별자·정규화 이메일이다.
    클라이언트가 제공자나 이메일을 지정할 수 없도록 가입 완료(1.6)는 이 값만 쓴다. 토큰 원문은 담지 않는다.
    배포 중 옛 형식이 남을 수 있으므로 필드는 추가만 하고, 모르는 필드는 무시한다(RefreshTokenSession과 같음).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OAuth2SignupContext(
        AuthProvider provider,
        String providerUserId,
        String email
) {

    public OAuth2SignupContext(AuthProvider provider, String providerUserId, String email) {
        // Redis에서 읽은 값에 필드가 빠졌을 때도 역직렬화 실패로 처리되도록 생성자에서 막는다
        if (provider == null || provider == AuthProvider.LOCAL
                || providerUserId == null || providerUserId.isBlank()
                || email == null || email.isBlank()) {
            throw new IllegalArgumentException("소셜 가입 컨텍스트의 provider, providerUserId, email은 필수입니다.");
        }
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.email = email;
    }

    // 로그·예외 메시지에 제공자 식별자·이메일이 남지 않게 한다(NFR-010)
    @Override
    public String toString() {
        return "OAuth2SignupContext[provider=" + provider + ", providerUserId=masked, email=masked]";
    }
}
