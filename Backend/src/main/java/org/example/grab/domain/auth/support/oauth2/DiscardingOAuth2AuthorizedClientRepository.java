package org.example.grab.domain.auth.support.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

/*
    제공자 Access Token을 보관하지 않는다(GR-46 제외 범위: 제공자 토큰 보관·제공자 API 추가 호출).
    기본 저장소(InMemoryOAuth2AuthorizedClientService)는 제공자 토큰을 (제공자, 사용자) 단위로 서버 메모리에 남긴다.
    같은 사용자의 재로그인은 덮어쓰지만 지우지 않아 서로 다른 사용자 수만큼 늘어나므로 바꾼다.
    ID 토큰으로 회원을 확인한 뒤에는 다시 꺼낼 일이 없다.
 */
public class DiscardingOAuth2AuthorizedClientRepository implements OAuth2AuthorizedClientRepository {

    @Override
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
            String clientRegistrationId, Authentication principal, HttpServletRequest request) {
        return null;
    }

    @Override
    public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient, Authentication principal,
                                     HttpServletRequest request, HttpServletResponse response) {
        // 저장하지 않는다
    }

    @Override
    public void removeAuthorizedClient(String clientRegistrationId, Authentication principal,
                                       HttpServletRequest request, HttpServletResponse response) {
        // 저장한 것이 없다
    }
}
