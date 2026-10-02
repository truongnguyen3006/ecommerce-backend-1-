package com.myexampleproject.cartservice;
import com.myexampleproject.cartservice.config.SecurityConfig;
import com.myexampleproject.cartservice.controller.CartController;
import com.myexampleproject.cartservice.service.CartService;
import com.myexampleproject.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.mockito.Mockito.*;

@WebMvcTest(CartController.class)
@Import({SecurityConfig.class,GlobalExceptionHandler.class})
class CartSecurityTests {
    @Autowired MockMvc mvc;
    @MockitoBean CartService service;
    @MockitoBean JwtDecoder decoder;
    @Test void cartRequiresLogin() throws Exception { mvc.perform(get("/api/cart/me")).andExpect(status().isUnauthorized()); }
    @Test void cannotReadOrMutateAnotherUsersCart() throws Exception {
        var user=jwt().jwt(j -> j.subject("A")).authorities(new SimpleGrantedAuthority("ROLE_USER"));
        mvc.perform(get("/api/cart/view/B").with(user)).andExpect(status().isForbidden());
        mvc.perform(post("/api/cart/add/B").with(user).contentType("application/json").content("{\"skuCode\":\"SKU\",\"quantity\":1}")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void invalidQuantityFailsBeforeServiceInvocation() throws Exception {
        mvc.perform(post("/api/cart/items").with(jwt().jwt(j -> j.subject("A")).authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .contentType("application/json").content("{\"skuCode\":\"SKU\",\"quantity\":0}")).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    @Test void currentUserUpdatesAndRemovesOwnItems() throws Exception {
        var user=jwt().jwt(j -> j.subject("A")).authorities(new SimpleGrantedAuthority("ROLE_USER"));
        mvc.perform(put("/api/cart/items/SKU").with(user).contentType("application/json").content("{\"skuCode\":\"SKU\",\"quantity\":2}")).andExpect(status().isOk());
        mvc.perform(delete("/api/cart/items/SKU").with(user)).andExpect(status().isNoContent());
        verify(service).updateQuantity(eq("A"),argThat(i -> i.getQuantity()==2));verify(service).removeItem("A","SKU");
    }
}
