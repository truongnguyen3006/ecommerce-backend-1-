package com.myexampleproject.productservice.dto;
import java.util.List;
public record ProductPage(List<ProductResponse> content, int page, int size, long totalElements, int totalPages) {}
