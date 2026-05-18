package com.modu.backend.domain.user.service;

import com.modu.backend.domain.user.dto.MyInfoResponse;
import com.modu.backend.domain.user.entity.KisCredential;
import com.modu.backend.domain.user.entity.User;
import com.modu.backend.domain.user.repository.KisCredentialRepository;
import com.modu.backend.domain.user.repository.UserRepository;
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

    public MyInfoResponse getMyInfo(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("인증된 사용자를 찾을 수 없습니다. userId=" + userId));
        KisCredential credential = kisCredentialRepository.findByUserId(userId).orElse(null);
        return MyInfoResponse.of(user, credential);
    }
}
