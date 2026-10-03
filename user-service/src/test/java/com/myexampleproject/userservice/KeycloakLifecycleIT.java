package com.myexampleproject.userservice;
import com.myexampleproject.userservice.service.*;
import com.myexampleproject.userservice.controller.AuthController;
import com.myexampleproject.userservice.dto.*;
import com.myexampleproject.userservice.model.User;
import com.myexampleproject.userservice.repository.*;
import com.myexampleproject.common.exception.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
/** Explicit opt-in integration suite: launcher must own the disposable Keycloak process. Never uses owner credentials. */
@SpringBootTest(classes=KeycloakLifecycleIT.Config.class,webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={
 "spring.datasource.url=jdbc:h2:mem:keycloak_fixture;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
 "spring.datasource.username=sa","spring.datasource.password=","spring.datasource.driver-class-name=org.h2.Driver",
 "spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true","eureka.client.enabled=false","spring.cloud.discovery.enabled=false",
 "keycloak.server-url=${TEST_KEYCLOAK_URL}","keycloak.realm=${TEST_KEYCLOAK_REALM}","keycloak.client-id=${TEST_KEYCLOAK_CLIENT_ID}",
 "keycloak.client-secret=${TEST_KEYCLOAK_CLIENT_SECRET}","keycloak.user-role=USER"})
class KeycloakLifecycleIT {
 @Autowired KeycloakService provider;@Autowired UserService service;@Autowired ProvisioningService provisioning;@Autowired AuthController auth;
 @Autowired UserRepository users;@Autowired JdbcTemplate jdbc;@Autowired PlatformTransactionManager manager;
 UserRequest request() {String name="fixture-"+UUID.randomUUID();return UserRequest.builder().username(name).email(name+"@example.test").password(UUID.randomUUID()+"-Aa1!").fullName("Disposable fixture").build();}
 UserResponse register(UserRequest request,String key) {return service.createUser(request,key);}
 JsonNode login(UserRequest request) {var login=new LoginRequest();login.setUsername(request.getUsername());login.setPassword(request.getPassword());return (JsonNode)auth.login(login).getBody();}
 JsonNode claims(JsonNode token) throws Exception {return new ObjectMapper().readTree(Base64.getUrlDecoder().decode(token.path("access_token").asText().split("\\.")[1]));}
 @Test void registerLoginRefreshLogoutAndInvalidRefreshPreserveExactIdentityAndUserRole() throws Exception {
  var request=request();String key=UUID.randomUUID().toString();var user=register(request,key);
  assertThat(provider.getKeycloakIdByUsername(request.getUsername())).isEqualTo(user.getKeycloakId());
  var token=login(request);assertThat(claims(token).path("realm_access").path("roles").toString()).contains("USER").doesNotContain("ADMIN");
  var refreshed=(JsonNode)auth.refreshToken(new TokenRefreshRequest(token.path("refresh_token").asText())).getBody();assertThat(refreshed.path("access_token").asText()).isNotBlank();
  auth.logout(refreshed.path("refresh_token").asText());
  assertThatThrownBy(() -> auth.refreshToken(new TokenRefreshRequest(refreshed.path("refresh_token").asText()))).isInstanceOfSatisfying(DomainException.class,e -> assertThat(e.getCode()).isEqualTo("INVALID_REFRESH_TOKEN"));
  assertThat(service.createUser(request,key).getId()).isEqualTo(user.getId());
 }
 @Test void disabledAccountHasItsOwnErrorCode() {
  var request=request();var user=register(request,UUID.randomUUID().toString());provider.updateUserInKeycloak(user.getKeycloakId(),Map.of("enabled",false));
  assertThatThrownBy(() -> login(request)).isInstanceOfSatisfying(DomainException.class,e -> {assertThat(e.getCode()).isEqualTo("ACCOUNT_DISABLED");assertThat(e.getStatusCode().value()).isEqualTo(403);});
 }
 @Test void exactFixtureAdminGrantIsVisibleAndDuplicateUsernameCannotProvisionAgain() throws Exception {
  var request=request();var user=register(request,UUID.randomUUID().toString());provider.assignRealmRoleToUser(user.getKeycloakId(),"ADMIN");
  assertThat(claims(login(request)).path("realm_access").path("roles").toString()).contains("ADMIN");
  assertThat(provider.getKeycloakIdByUsername(request.getUsername()+"2")).isNull();
  assertThatThrownBy(() -> service.createUser(request,UUID.randomUUID().toString())).isInstanceOfSatisfying(DomainException.class,e -> assertThat(e.getCode()).isEqualTo("USER_ALREADY_EXISTS"));
 }
 @Test void actualProviderCreationThenSqlFailureRecoversWithSameManagedOrigin() {
  var request=request();String key=UUID.randomUUID().toString();var faulty=mock(UserRepository.class,org.mockito.AdditionalAnswers.delegatesTo(users));
  doThrow(new IllegalStateException("Injected fixture SQL failure")).when(faulty).saveAndFlush(any(User.class));
  var first=new ProvisioningService(jdbc,faulty,provider,manager);ReflectionTestUtils.setField(first,"userRole","USER");
  assertThatThrownBy(() -> first.register(request,key)).isInstanceOfSatisfying(DomainException.class,e -> assertThat(e.getCode()).isEqualTo("PROVISIONING_RETRY"));
  String createdId=provider.getKeycloakIdByUsername(request.getUsername());assertThat(createdId).isNotBlank();assertThat(users.findByKeycloakId(createdId)).isEmpty();
  assertThat(provisioning.register(request,key).getKeycloakId()).isEqualTo(createdId);
  assertThat(jdbc.queryForObject("SELECT state FROM user_provisioning_intent WHERE idempotency_key=?",String.class,key)).isEqualTo("COMPLETE");
 }
 @Test void partialProfileUpdatePreservesAdminOnlyOriginMarker() {
  var request=request();String key=UUID.randomUUID().toString();var user=register(request,key);var update=new UserRequest();update.setPhoneNumber("fixture-phone");
  service.updateSelfUser(user.getKeycloakId(),update);
  String origin=jdbc.queryForObject("SELECT intent_id FROM user_provisioning_intent WHERE idempotency_key=?",String.class,key);
  assertThat(provider.ensureProvisionedUser(request,origin,user.getKeycloakId())).isEqualTo(user.getKeycloakId());
 }
 @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration(exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration.class)
 @EntityScan(basePackageClasses=User.class) @EnableJpaRepositories(basePackageClasses=UserRepository.class)
 static class Config {
  @Bean KeycloakService provider(WebClient.Builder builder) {return new KeycloakService(builder);}
  @Bean ProvisioningService provisioning(JdbcTemplate j,UserRepository u,KeycloakService p,PlatformTransactionManager m) {return new ProvisioningService(j,u,p,m);}
  @Bean UserService service(UserRepository u,UserAddressRepository a,KeycloakService p,ProvisioningService r) {return new UserService(u,a,p,r);}
  @Bean AuthController auth(WebClient.Builder builder,KeycloakService p,UserService s) {return new AuthController(builder,p,s);}
 }
}
