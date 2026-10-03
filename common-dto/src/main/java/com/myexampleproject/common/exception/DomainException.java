package com.myexampleproject.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class DomainException extends ResponseStatusException {
    private final String code;
    public DomainException(HttpStatus status, String code, String message) {
        super(status,message);this.code=code;
    }
    public String getCode() {return code;}
}
