package com.modu.backend.global.error;

import org.springframework.http.HttpStatus;

public interface ErrorCode {
    String getCode();
    HttpStatus getStatus();
    String getDefaultMessage();
}
