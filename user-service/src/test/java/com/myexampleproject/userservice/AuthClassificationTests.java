package com.myexampleproject.userservice;
import com.myexampleproject.userservice.controller.AuthController;
import com.myexampleproject.userservice.service.*;
import com.myexampleproject.userservice.dto.LoginRequest;
import com.myexampleproject.common.exception.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.reactive.function.client.*;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class AuthClassificationTests {
    AuthController controller(HttpStatus status,String body) {
        var builder=WebClient.builder().exchangeFunction(req -> Mono.just(ClientResponse.create(status).header("Content-Type","application/json").body(body).build()));
        var controller=new AuthController(builder,mock(KeycloakService.class),mock(UserService.class));
        ReflectionTestUtils.setField(controller,"keycloakServerUrl","https://identity.invalid");ReflectionTestUtils.setField(controller,"keycloakRealm","fixture");
        ReflectionTestUtils.setField(controller,"keycloakClientId","fixture");ReflectionTestUtils.setField(controller,"keycloakClientSecret","fixture-secret");return controller;
    }
    void fails(Runnable operation,String code,int status) {assertThatThrownBy(operation::run).isInstanceOfSatisfying(DomainException.class,e -> {assertThat(e.getCode()).isEqualTo(code);assertThat(e.getStatusCode().value()).isEqualTo(status);assertThat(e.getReason()).doesNotContain("fixture-secret");});}
    LoginRequest login() {var r=new LoginRequest();r.setUsername("fixture");r.setPassword("fixture-password");return r;}
    @Test void invalidCredentialsAndExpiredRefreshHaveDifferentCodes() {
        var c=controller(HttpStatus.BAD_REQUEST,"{\"error\":\"invalid_grant\",\"error_description\":\"Invalid user credentials\"}");
        fails(() -> c.login(login()),"INVALID_CREDENTIALS",401);fails(() -> c.refreshToken(new com.myexampleproject.userservice.dto.TokenRefreshRequest("fixture-refresh")),"INVALID_REFRESH_TOKEN",401);
    }
    @Test void disabledAndForbiddenAccountsAreNotIncorrectPasswords() {
        fails(() -> controller(HttpStatus.BAD_REQUEST,"{\"error\":\"invalid_grant\",\"error_description\":\"Account disabled\"}").login(login()),"ACCOUNT_DISABLED",403);
        fails(() -> controller(HttpStatus.FORBIDDEN,"{}").login(login()),"AUTH_FORBIDDEN",403);
    }
    @Test void invalidClientAndOutageAreTemporaryServiceFailures() {
        fails(() -> controller(HttpStatus.UNAUTHORIZED,"{\"error\":\"invalid_client\"}").refreshToken(new com.myexampleproject.userservice.dto.TokenRefreshRequest("refresh")),"AUTH_CONFIGURATION_UNAVAILABLE",503);
        fails(() -> controller(HttpStatus.BAD_GATEWAY,"fixture-secret").login(login()),"AUTH_UPSTREAM_UNAVAILABLE",503);
    }
    @Test void missingAndMalformedTokensAreRejectedSafely() {
        fails(() -> controller(HttpStatus.OK,"{\"access_token\":\"a\"}").refreshToken(new com.myexampleproject.userservice.dto.TokenRefreshRequest("refresh")),"AUTH_UPSTREAM_INVALID_RESPONSE",503);
        fails(() -> controller(HttpStatus.OK,"not-json").login(login()),"AUTH_UPSTREAM_INVALID_RESPONSE",503);
    }
    @Test void validTokenResponseRemainsUsable() {assertThat(controller(HttpStatus.OK,"{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":300}").login(login()).getStatusCode()).isEqualTo(HttpStatus.OK);}
}
