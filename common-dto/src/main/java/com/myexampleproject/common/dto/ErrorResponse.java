package com.myexampleproject.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ErrorResponse {
    private String errorCode; // Ví dụ: ORDER_NOT_FOUND, OUT_OF_STOCK
    private String message;   // Message chi tiết cho Dev/User
    private int status;       // HTTP Status Code (400, 404, 500)
    private String timestamp;
}