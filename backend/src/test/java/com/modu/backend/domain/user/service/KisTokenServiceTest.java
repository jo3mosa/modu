package com.modu.backend.domain.user.service;

import com.modu.backend.domain.user.client.KisTokenClient;
import com.modu.backend.domain.user.entity.KisToken;
import com.modu.backend.domain.user.repository.KisTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KisTokenServiceTest {

    @Mock KisTokenRepository kisTokenRepository;
    @Mock KisTokenClient kisTokenClient;

    @InjectMocks
    KisTokenService kisTokenService;

    // ── Access Token ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("유효한 액세스 토큰이 DB에 있으면 KIS API 호출 없이 재사용")
    void 유효한_액세스_토큰_캐시_재사용() {
        // given
        KisToken cachedToken = KisToken.builder()
                .userId(1L)
                .tokenType("ACCESS_TOKEN")
                .accessToken("cached-access-token")
                .issuedAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusHours(12))
                .build();

        when(kisTokenRepository.findValidToken(eq(1L), eq("ACCESS_TOKEN"), any()))
                .thenReturn(Optional.of(cachedToken));

        // when
        String result = kisTokenService.getOrIssueAccessToken(1L, "appKey", "appSecret");

        // then
        assertThat(result).isEqualTo("cached-access-token");
        verify(kisTokenClient, never()).issueAccessToken(anyString(), anyString());
    }

    @Test
    @DisplayName("유효한 액세스 토큰이 없으면 KIS API 호출 후 DB 저장")
    void 액세스_토큰_없을_때_신규_발급_및_저장() {
        // given
        when(kisTokenRepository.findValidToken(eq(1L), eq("ACCESS_TOKEN"), any()))
                .thenReturn(Optional.empty());
        when(kisTokenClient.issueAccessToken("appKey", "appSecret"))
                .thenReturn("new-access-token");

        // when
        String result = kisTokenService.getOrIssueAccessToken(1L, "appKey", "appSecret");

        // then
        assertThat(result).isEqualTo("new-access-token");
        verify(kisTokenClient).issueAccessToken("appKey", "appSecret");
        verify(kisTokenRepository).save(any(KisToken.class));
    }

    @Test
    @DisplayName("액세스 토큰 신규 발급 시 저장되는 tokenType이 ACCESS_TOKEN인지 확인")
    void 액세스_토큰_저장_시_tokenType_확인() {
        // given
        when(kisTokenClient.issueAccessToken("appKey", "appSecret"))
                .thenReturn("new-access-token");

        // when
        kisTokenService.issueAndSaveAccessToken(1L, "appKey", "appSecret");

        // then
        ArgumentCaptor<KisToken> captor = ArgumentCaptor.forClass(KisToken.class);
        verify(kisTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getTokenType()).isEqualTo("ACCESS_TOKEN");
        assertThat(captor.getValue().getAccessToken()).isEqualTo("new-access-token");
        assertThat(captor.getValue().getUserId()).isEqualTo(1L);
    }

    // ── WebSocket Key ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("유효한 웹소켓 접속키가 DB에 있으면 KIS API 호출 없이 재사용")
    void 유효한_웹소켓키_캐시_재사용() {
        // given
        KisToken cachedKey = KisToken.builder()
                .userId(1L)
                .tokenType("WEBSOCKET_KEY")
                .accessToken("cached-ws-key")
                .issuedAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusHours(12))
                .build();

        when(kisTokenRepository.findValidToken(eq(1L), eq("WEBSOCKET_KEY"), any()))
                .thenReturn(Optional.of(cachedKey));

        // when
        String result = kisTokenService.getOrIssueWebSocketKey(1L, "appKey", "appSecret");

        // then
        assertThat(result).isEqualTo("cached-ws-key");
        verify(kisTokenClient, never()).issueWebSocketKey(anyString(), anyString());
    }

    @Test
    @DisplayName("웹소켓 접속키 신규 발급 시 저장되는 tokenType이 WEBSOCKET_KEY인지 확인")
    void 웹소켓키_저장_시_tokenType_확인() {
        // given
        when(kisTokenClient.issueWebSocketKey("appKey", "appSecret"))
                .thenReturn("new-ws-key");

        // when
        kisTokenService.issueAndSaveWebSocketKey(1L, "appKey", "appSecret");

        // then
        ArgumentCaptor<KisToken> captor = ArgumentCaptor.forClass(KisToken.class);
        verify(kisTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getTokenType()).isEqualTo("WEBSOCKET_KEY");
        assertThat(captor.getValue().getAccessToken()).isEqualTo("new-ws-key");
    }
}
