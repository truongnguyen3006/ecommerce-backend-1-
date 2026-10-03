package com.myexampleproject.userservice;
import com.myexampleproject.userservice.service.KeycloakService;
import com.myexampleproject.userservice.dto.UserRequest;
import com.myexampleproject.common.exception.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.reactive.function.client.*;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;
class ProvisioningIdentityTests {
 KeycloakService provider(String users) {
  var builder=WebClient.builder().exchangeFunction(req -> Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type","application/json")
   .body(req.url().getPath().endsWith("/token")?"{\"access_token\":\"fixture-token\"}":users).build()));
  var p=new KeycloakService(builder);ReflectionTestUtils.setField(p,"keycloakServerUrl","https://identity.invalid");ReflectionTestUtils.setField(p,"keycloakRealm","fixture");
  ReflectionTestUtils.setField(p,"keycloakClientId","fixture");ReflectionTestUtils.setField(p,"keycloakClientSecret","fixture-secret");return p;
 }
 UserRequest request() {return UserRequest.builder().username("fixture").email("fixture@example.test").password("fixture-password").build();}
 @Test void recoveryRequiresExactUsernameEmailAndOrigin() {
  var p=provider("[{\"id\":\"ours\",\"username\":\"fixture\",\"email\":\"fixture@example.test\",\"attributes\":{\"project1ProvisioningId\":[\"origin\"]}}]");
  assertThat(p.ensureProvisionedUser(request(),"origin",null)).isEqualTo("ours");
  assertThatThrownBy(() -> p.ensureProvisionedUser(request(),"different",null)).isInstanceOf(DomainException.class);
  assertThatThrownBy(() -> p.ensureProvisionedUser(request(),"origin","other-id")).isInstanceOf(DomainException.class);
 }
 @Test void legitimatePreexistingAndSubstringIdentityAreNeverAdopted() {
  for(String username:new String[]{"fixture","fixture2"}) {
   var p=provider("[{\"id\":\"legitimate\",\"username\":\""+username+"\",\"email\":\"fixture@example.test\"}]");
   assertThatThrownBy(() -> p.ensureProvisionedUser(request(),"origin",null)).isInstanceOfSatisfying(DomainException.class,e -> assertThat(e.getCode()).isEqualTo("USER_ALREADY_EXISTS"));
  }
 }
}
