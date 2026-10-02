package com.myexampleproject.productservice.controller;

import com.myexampleproject.productservice.dto.CloudinaryUploadResponse;
import com.myexampleproject.productservice.service.CloudinaryImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/product/uploads")
@RequiredArgsConstructor
public class ProductUploadController {

    private final CloudinaryImageService cloudinaryImageService;

    @PostMapping("/image")
    @ResponseStatus(HttpStatus.OK)
    public CloudinaryUploadResponse uploadImage(@RequestParam("file") MultipartFile file) {
        return cloudinaryImageService.uploadSingleImage(file);
    }

    @PostMapping("/gallery")
    @ResponseStatus(HttpStatus.OK)
    public List<CloudinaryUploadResponse> uploadGallery(@RequestParam("files") List<MultipartFile> files) {
        return cloudinaryImageService.uploadMultipleImages(files);
    }
}
