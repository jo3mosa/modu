package com.modu.backend.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * RestClient 빈 설정
 *
 * 외부 API 호출 시 무한 대기를 방지하기 위해 타임아웃 설정
 * - connectTimeout: 3초 (서버 연결 시도 제한)
 * - readTimeout   : 5초 (응답 수신 대기 제한)
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient kakaoRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));

        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}
