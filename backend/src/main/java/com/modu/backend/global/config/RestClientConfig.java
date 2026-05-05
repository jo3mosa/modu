package com.modu.backend.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * RestClient 빈 설정
 *
 * 외부 API 호출 시 무한 대기를 방지하기 위해 타임아웃 설정
 * - kakaoRestClient: connectTimeout 3초, readTimeout 5초
 * - kisRestClient  : connectTimeout 5초, readTimeout 10초 (KIS API 응답이 다소 느릴 수 있음)
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

    @Bean
    public RestClient kisRestClient(@Value("${kis.api.url}") String kisBaseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));

        return RestClient.builder()
                .baseUrl(kisBaseUrl)
                .requestFactory(factory)
                .build();
    }
}
