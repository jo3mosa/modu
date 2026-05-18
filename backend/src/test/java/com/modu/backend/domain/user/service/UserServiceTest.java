package com.modu.backend.domain.user.service;

import com.modu.backend.domain.auth.exception.AuthErrorCode;
import com.modu.backend.domain.user.dto.MyInfoResponse;
import com.modu.backend.domain.user.entity.KisCredential;
import com.modu.backend.domain.user.entity.User;
import com.modu.backend.domain.user.repository.KisCredentialRepository;
import com.modu.backend.domain.user.repository.UserRepository;
import com.modu.backend.global.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock UserRepository userRepository;
    @Mock KisCredentialRepository kisCredentialRepository;

    @InjectMocks
    UserService userService;

    private User testUser;
    private KisCredential testCredential;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .providerId("123456789")
                .provider("kakao")
                .nickname("홍길동")
                .email("user@example.com")
                .build();

        testCredential = KisCredential.builder()
                .userId(1L)
                .appKeyEnc("encrypted-app-key")
                .appSecretEnc("encrypted-app-secret")
                .accountNo("50012345")
                .accountPrdtCd("01")
                .isRealAccount(true)
                .build();
    }

    @Test
    @DisplayName("KIS 연동된 사용자 — 프로필 + 연동 상태(accountNo 결합) 반환")
    void 내정보_조회_KIS_연동됨() {
        // given
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(testCredential));

        // when
        MyInfoResponse response = userService.getMyInfo(1L);

        // then
        assertThat(response.name()).isEqualTo("홍길동");
        assertThat(response.email()).isEqualTo("user@example.com");
        assertThat(response.socialProvider()).isEqualTo("kakao");
        assertThat(response.createdAt()).isEqualTo(testUser.getCreatedAt());
        assertThat(response.kisKeyStatus().isConnected()).isTrue();
        assertThat(response.kisKeyStatus().accountNo()).isEqualTo("50012345-01");
    }

    @Test
    @DisplayName("KIS 미연동 사용자 — kisKeyStatus.isConnected=false, accountNo=null")
    void 내정보_조회_KIS_미연동() {
        // given
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.empty());

        // when
        MyInfoResponse response = userService.getMyInfo(1L);

        // then
        assertThat(response.name()).isEqualTo("홍길동");
        assertThat(response.kisKeyStatus().isConnected()).isFalse();
        assertThat(response.kisKeyStatus().accountNo()).isNull();
    }

    @Test
    @DisplayName("이메일 미동의 소셜 사용자 — email 필드 null 유지하여 반환")
    void 이메일_null_사용자_조회() {
        // given
        User userWithoutEmail = User.builder()
                .providerId("987654321")
                .provider("kakao")
                .nickname("닉네임만있음")
                .email(null)
                .build();
        when(userRepository.findById(2L)).thenReturn(Optional.of(userWithoutEmail));
        when(kisCredentialRepository.findByUserId(2L)).thenReturn(Optional.empty());

        // when
        MyInfoResponse response = userService.getMyInfo(2L);

        // then
        assertThat(response.email()).isNull();
        assertThat(response.name()).isEqualTo("닉네임만있음");
    }

    @Test
    @DisplayName("존재하지 않는 userId — ApiException(AUTH_007 USER_NOT_FOUND, 404)")
    void 사용자_없음_시_도메인_예외() {
        // given
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> userService.getMyInfo(99L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(AuthErrorCode.USER_NOT_FOUND));
    }
}
