package com.myexampleproject.productservice.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CloudinaryUploadResponse {
    private String publicId;
    private String secureUrl;
    private Integer width;
    private Integer height;
    private String originalFilename;
}
