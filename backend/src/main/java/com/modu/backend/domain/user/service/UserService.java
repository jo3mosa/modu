package com.modu.backend.domain.user.service;

import com.modu.backend.domain.auth.exception.AuthErrorCode;
import com.modu.backend.domain.user.dto.MyInfoResponse;
import com.modu.backend.domain.user.entity.KisCredential;
import com.modu.backend.domain.user.entity.User;
import com.modu.backend.domain.user.repository.KisCredentialRepository;
import com.modu.backend.domain.user.repository.UserRepository;
import com.modu.backend.global.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 프로필 조회 서비스
 *
 * 마이페이지 진입 시 프로필 + KIS 연동 상태를 한 번에 반환
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final KisCredentialRepository kisCredentialRepository;

    /**
     * JWT는 유효하지만 사용자 레코드가 없는 경우(탈퇴 등)는
     * 서버 결함이 아닌 도메인 예외로 처리한다.
     * AuthErrorCode.USER_NOT_FOUND(AUTH_007) → 404 응답으로 매핑된다.
     */
    public MyInfoResponse getMyInfo(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(AuthErrorCode.USER_NOT_FOUND));
        KisCredential credential = kisCredentialRepository.findByUserId(userId).orElse(null);
        return MyInfoResponse.of(user, credential);
    }
}
