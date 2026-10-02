package com.myexampleproject.productservice;

import com.myexampleproject.productservice.controller.ProductController;
import com.myexampleproject.productservice.config.SecurityConfig;
import com.myexampleproject.productservice.service.ProductService;
import com.myexampleproject.productservice.repository.ProductRepository;
import com.myexampleproject.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

@WebMvcTest(ProductController.class)
@Import({SecurityConfig.class,GlobalExceptionHandler.class})
class ProductSecurityTests {
    @Autowired MockMvc mvc;
    @MockitoBean ProductService service;
    @MockitoBean ProductRepository repository;
    @MockitoBean KafkaTemplate<String,Object> kafka;
    @MockitoBean JwtDecoder decoder;
    @Test void productBrowsingIsPublic() throws Exception { mvc.perform(get("/api/product")).andExpect(status().isOk()); }
    @Test void mutationsRequireAuthenticationAndAdmin() throws Exception {
        mvc.perform(post("/api/product").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/product/1").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))).contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }
    @Test void cacheWarmupIsNotPublic() throws Exception {
        mvc.perform(get("/api/product/admin/warm-cache")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/product/admin/warm-cache").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))).andExpect(status().isForbidden());
    }
    @Test void adminValidationUsesJakartaAndSafeErrorShape() throws Exception {
        mvc.perform(post("/api/product").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType("application/json").content("{\"name\":\" \",\"basePrice\":-1,\"variants\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.path").value("/api/product"));
    }
}
