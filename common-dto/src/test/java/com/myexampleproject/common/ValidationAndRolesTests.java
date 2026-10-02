package com.myexampleproject.common;
import com.myexampleproject.common.dto.*;
import com.myexampleproject.common.security.KeycloakRoles;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.Map;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
class ValidationAndRolesTests {
    @Test void quantitiesAreValidatedWithJakarta() {try(var factory=Validation.buildDefaultValidatorFactory()){var validator=factory.getValidator();assertThat(validator.validate(new OrderLineItemRequest("SKU",0))).isNotEmpty();assertThat(validator.validate(new CartItemRequest(" ",1))).isNotEmpty();assertThat(validator.validate(new OrderLineItemRequest("SKU",1))).isEmpty();}}
    @Test void realmRolesAreCaseInsensitiveAndMalformedClaimsFailClosed() {
        Jwt jwt=Jwt.withTokenValue("test").header("alg","none").subject("A").claim("realm_access",Map.of("roles",List.of("admin","user"))).build();
        assertThat(KeycloakRoles.isAdmin(jwt)).isTrue();assertThat(KeycloakRoles.authorities(jwt)).extracting(a -> a.getAuthority()).contains("ROLE_ADMIN","ROLE_USER");
        Jwt malformed=Jwt.withTokenValue("test").header("alg","none").subject("A").claim("realm_access","wrong").build();assertThat(KeycloakRoles.isAdmin(malformed)).isFalse();
    }
}
