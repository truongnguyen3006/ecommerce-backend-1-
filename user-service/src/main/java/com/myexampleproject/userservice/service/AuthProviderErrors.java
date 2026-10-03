package com.myexampleproject.userservice.service;

import com.myexampleproject.common.exception.DomainException;
import com.fasterxml.jackson.databind.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import java.util.Locale;

public final class AuthProviderErrors {
    private AuthProviderErrors() {}
    public static DomainException classify(WebClientResponseException error, boolean refresh) {
        JsonNode body;
        try {body=new ObjectMapper().readTree(error.getResponseBodyAsString());}
        catch(Exception malformed) {body=new ObjectMapper().createObjectNode();}
        String code=body==null?"":body.path("error").asText("");
        String description=body==null?"":body.path("error_description").asText("").toLowerCase(Locale.ROOT);
        if (error.getStatusCode().is5xxServerError()) return unavailable();
        if ("invalid_client".equals(code) || "unauthorized_client".equals(code))
            return new DomainException(HttpStatus.SERVICE_UNAVAILABLE,"AUTH_CONFIGURATION_UNAVAILABLE","Authentication configuration unavailable");
        if ("invalid_grant".equals(code)) {
            if (description.contains("account disabled") || description.contains("user disabled"))
                return new DomainException(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED","Account disabled");
            return new DomainException(HttpStatus.UNAUTHORIZED,refresh?"INVALID_REFRESH_TOKEN":"INVALID_CREDENTIALS","Authentication grant rejected");
        }
        if (error.getStatusCode().value()==403) return new DomainException(HttpStatus.FORBIDDEN,"AUTH_FORBIDDEN","Authentication not permitted");
        return malformed();
    }
    public static DomainException unavailable() {return new DomainException(HttpStatus.SERVICE_UNAVAILABLE,"AUTH_UPSTREAM_UNAVAILABLE","Authentication service unavailable");}
    public static DomainException malformed() {return new DomainException(HttpStatus.SERVICE_UNAVAILABLE,"AUTH_UPSTREAM_INVALID_RESPONSE","Invalid authentication response");}
    public static void validateTokens(JsonNode response) {
        if (response==null || !response.path("access_token").isTextual() || response.path("access_token").asText().isBlank()
                || !response.path("refresh_token").isTextual() || response.path("refresh_token").asText().isBlank()
                || response.path("expires_in").asLong(0)<=0) throw malformed();
    }
}
