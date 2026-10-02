package com.myexampleproject.common.dto;

import lombok.*;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ErrorResponse {
    private String timestamp;
    private int status;
    private String code;
    private String message;
    private String path;

    // Preserve the old response field for existing consumers.
    public String getErrorCode() { return code; }
}
