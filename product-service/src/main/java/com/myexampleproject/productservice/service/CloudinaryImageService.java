package com.myexampleproject.productservice.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.myexampleproject.productservice.dto.CloudinaryUploadResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CloudinaryImageService {

    @Value("${cloudinary.cloud-name:}")
    private String cloudName;

    @Value("${cloudinary.api-key:}")
    private String apiKey;

    @Value("${cloudinary.api-secret:}")
    private String apiSecret;

    @Value("${cloudinary.product-folder:ecommerce/products}")
    private String productFolder;

    public CloudinaryUploadResponse uploadSingleImage(MultipartFile file) {
        validateImage(file);
        try {
            Map<?, ?> result = createCloudinary().uploader().upload(
                    file.getBytes(),
                    ObjectUtils.asMap(
                            "folder", productFolder,
                            "public_id", UUID.randomUUID().toString(),
                            "resource_type", "image",
                            "overwrite", false,
                            "use_filename", false,
                            "unique_filename", true
                    )
            );
            return mapResult(file.getOriginalFilename(), result);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Không thể upload ảnh lên Cloudinary", e);
        }
    }

    public List<CloudinaryUploadResponse> uploadMultipleImages(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chưa có file ảnh nào được gửi lên");
        }
        List<CloudinaryUploadResponse> results = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file != null && !file.isEmpty()) {
                results.add(uploadSingleImage(file));
            }
        }
        if (results.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không có file ảnh hợp lệ để upload");
        }
        return results;
    }

    private Cloudinary createCloudinary() {
        if (!StringUtils.hasText(cloudName) || !StringUtils.hasText(apiKey) || !StringUtils.hasText(apiSecret)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cloudinary chưa được cấu hình cloudName/apiKey/apiSecret");
        }
        Cloudinary cloudinary = new Cloudinary(ObjectUtils.asMap(
                "cloud_name", cloudName,
                "api_key", apiKey,
                "api_secret", apiSecret,
                "secure", true
        ));
        cloudinary.config.secure = true;
        return cloudinary;
    }

    private void validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File ảnh không hợp lệ hoặc đang rỗng");
        }
        String contentType = file.getContentType();
        if (!StringUtils.hasText(contentType) || !contentType.toLowerCase().startsWith("image/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chỉ hỗ trợ upload file ảnh");
        }
    }

    private CloudinaryUploadResponse mapResult(String originalFilename, Map<?, ?> result) {
        return CloudinaryUploadResponse.builder()
                .publicId((String) result.get("public_id"))
                .secureUrl((String) result.get("secure_url"))
                .width(result.get("width") instanceof Number number ? number.intValue() : null)
                .height(result.get("height") instanceof Number number ? number.intValue() : null)
                .originalFilename(originalFilename)
                .build();
    }
}
