package com.myexampleproject.userservice;

import com.myexampleproject.userservice.service.KeycloakService;
import com.myexampleproject.userservice.config.UserSeeder;
import com.myexampleproject.userservice.repository.UserRepository;
import com.myexampleproject.userservice.dto.UserRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExactBootstrapTests {
    List<String> requests = new ArrayList<>();
    KeycloakService client(String users) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            requests.add(request.method() + " " + request.url());
            String path = request.url().getPath();
            if (path.endsWith("/token")) return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body("{\"access_token\":\"test-token\"}").build());
            if (path.endsWith("/users") && request.method().name().equals("GET")) {
                assertThat(request.url().getQuery()).contains("exact=true");
                return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(users).build());
            }
            if (path.endsWith("/users")) return Mono.just(ClientResponse.create(HttpStatus.CREATED)
                    .header("Location", "https://identity.invalid/admin/realms/test/users/new-user").build());
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body("{}").build());
        });
        KeycloakService service = new KeycloakService(builder);
        ReflectionTestUtils.setField(service, "keycloakServerUrl", "https://identity.invalid");
        ReflectionTestUtils.setField(service, "keycloakRealm", "test");
        ReflectionTestUtils.setField(service, "keycloakClientId", "test-client");
        ReflectionTestUtils.setField(service, "keycloakClientSecret", "test-only-secret");
        return service;
    }

    @Test void exactAdminRemainsUsable() {
        assertThat(client("[{\"id\":\"real-admin\",\"username\":\"admin\"}]").getKeycloakIdByUsername("admin")).isEqualTo("real-admin");
    }
    @Test void substringAccountsCanNeverReceiveBootstrapRolesEvenIfProviderIgnoresExact() {
        for (String username : List.of("0admin", "myadmin", "admin2")) {
            requests.clear();UserRepository repository = mock(UserRepository.class);
            UserSeeder seeder = new UserSeeder(client("[{\"id\":\"ordinary\",\"username\":\"" + username + "\"}]"), repository);
            ReflectionTestUtils.setField(seeder,"adminUsername","admin");ReflectionTestUtils.setField(seeder,"adminEmail","admin@example.com");
            assertThatThrownBy(() -> seeder.run()).isInstanceOf(IllegalStateException.class);
            assertThat(requests).noneMatch(r -> r.contains("role-mappings"));verifyNoInteractions(repository);
        }
    }
    @Test void ambiguousExactResponseIsRejected() {
        assertThatThrownBy(() -> client("[{\"id\":\"1\",\"username\":\"admin\"},{\"id\":\"2\",\"username\":\"admin\"}]")
                .getKeycloakIdByUsername("admin")).isInstanceOf(IllegalStateException.class);
    }
    @Test void exactDuplicatesConflictAndSubstringResultsDoNotRejectDistinctRegistration() {
        UserRequest request = UserRequest.builder().username("admin2").email("test@example.com").password("test-only-password").build();
        assertThat(client("[{\"id\":\"1\",\"username\":\"admin\"}]").createUserInKeycloak(request)).isEqualTo("new-user");
        assertThatThrownBy(() -> client("[{\"id\":\"2\",\"username\":\"admin2\"}]").createUserInKeycloak(request))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
