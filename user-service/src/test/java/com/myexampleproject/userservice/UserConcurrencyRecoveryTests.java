package com.myexampleproject.userservice;
import com.myexampleproject.userservice.service.*;
import com.myexampleproject.userservice.repository.*;
import com.myexampleproject.userservice.model.User;
import com.myexampleproject.userservice.dto.*;
import com.myexampleproject.common.exception.DomainException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
@SpringBootTest(classes=UserConcurrencyRecoveryTests.Config.class,webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={
 "spring.datasource.url=jdbc:h2:mem:user_concurrency;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
 "spring.datasource.username=sa","spring.datasource.password=","spring.datasource.driver-class-name=org.h2.Driver",
 "spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true","eureka.client.enabled=false","spring.cloud.discovery.enabled=false"})
class UserConcurrencyRecoveryTests {
 @Autowired UserRepository users;@Autowired UserAddressRepository addresses;@Autowired UserService service;
 @Autowired KeycloakService provider;@Autowired JdbcTemplate jdbc;@Autowired PlatformTransactionManager manager;
 @BeforeEach void clear() {addresses.deleteAll();users.deleteAll();jdbc.update("DELETE FROM user_provisioning_intent");reset(provider);users.saveAndFlush(User.builder().keycloakId("owner").email("existing@example.test").status(true).build());}
 UserAddressRequest address(String name) {var r=new UserAddressRequest();r.setRecipientName(name);r.setRecipientPhone("0123456789");r.setAddressLine("Fixture address");r.setIsDefault(true);return r;}
 UserRequest registration() {return UserRequest.builder().username("fixture").email("fixture@example.test").password("fixture-password").fullName("Fixture user").build();}
 @Test void concurrentDefaultCreationHasOneOwnerScopedWinnerAndDeletingItSelectsLowestId() throws Exception {
  var start=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)) {
   var one=pool.submit(() -> {start.await();return service.createAddress("owner",address("one"));});
   var two=pool.submit(() -> {start.await();return service.createAddress("owner",address("two"));});start.countDown();one.get(10,TimeUnit.SECONDS);two.get(10,TimeUnit.SECONDS);
  }
  var all=addresses.findAll();assertThat(all).hasSize(2);assertThat(all.stream().filter(a -> a.isDefault()).count()).isEqualTo(1);
  var selected=all.stream().filter(a -> a.isDefault()).findFirst().orElseThrow();service.deleteAddress("owner",selected.getId());
  assertThat(addresses.findAll()).hasSize(1);assertThat(addresses.findAll().getFirst().isDefault()).isTrue();
 }
 @Test void databaseRejectsASecondDefaultEvenWhenServiceIsBypassed() {
  service.createAddress("owner",address("one"));
  assertThatThrownBy(() -> jdbc.update("INSERT INTO t_user_address(user_keycloak_id,recipient_name,recipient_phone,address_line,is_default) VALUES('owner','other','0','fixture',true)"))
    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
 }
 @Test void providerCreatedButSqlFailedRegistrationResumesSameIntentWithoutWrongIdentityOrDeletion() {
  String key=UUID.randomUUID().toString();when(provider.ensureProvisionedUser(any(),anyString(),any())).thenReturn("owned-fixture-kc");
  var faulty=mock(UserRepository.class,org.mockito.AdditionalAnswers.delegatesTo(users));
  doThrow(new IllegalStateException("fixture SQL write failed")).when(faulty).saveAndFlush(any(User.class));
  var first=new ProvisioningService(jdbc,faulty,provider,manager);
  assertThatThrownBy(() -> first.register(registration(),key)).isInstanceOfSatisfying(DomainException.class,e -> assertThat(e.getCode()).isEqualTo("PROVISIONING_RETRY"));
  assertThat(jdbc.queryForObject("SELECT state FROM user_provisioning_intent",String.class)).isEqualTo("RECONCILIATION_REQUIRED");
  assertThat(users.findByKeycloakId("owned-fixture-kc")).isEmpty();
  var recovered=new ProvisioningService(jdbc,users,provider,manager).register(registration(),key);
  assertThat(recovered.getKeycloakId()).isEqualTo("owned-fixture-kc");
  assertThat(new ProvisioningService(jdbc,users,provider,manager).register(registration(),key).getId()).isEqualTo(recovered.getId());
  verify(provider,never()).deleteUser(anyString());verify(provider,times(2)).ensureProvisionedUser(any(),anyString(),any());
  assertThat(jdbc.queryForObject("SELECT state FROM user_provisioning_intent",String.class)).isEqualTo("COMPLETE");
 }
 @Test void roleAssignmentFailureRollsBackProfileAndRetryFinishesWithSameOrigin() {
  when(provider.ensureProvisionedUser(any(),anyString(),any())).thenReturn("retry-kc");doThrow(new IllegalStateException("fixture role outage")).doNothing().when(provider).assignRealmRoleToUser("retry-kc","user");
  var p=new ProvisioningService(jdbc,users,provider,manager);String key=UUID.randomUUID().toString();
  assertThatThrownBy(() -> p.register(registration(),key)).isInstanceOf(DomainException.class);assertThat(users.findByKeycloakId("retry-kc")).isEmpty();
  assertThat(p.register(registration(),key).getKeycloakId()).isEqualTo("retry-kc");
  assertThatThrownBy(() -> p.register(UserRequest.builder().username("changed").email("changed@example.test").password("fixture-password").build(),key))
   .isInstanceOfSatisfying(DomainException.class,e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));
 }
 @Test void existingSqlIdentityCannotTriggerExternalProvisioning() {
  var request=registration();request.setEmail("existing@example.test");
  assertThatThrownBy(() -> new ProvisioningService(jdbc,users,provider,manager).register(request,UUID.randomUUID().toString())).isInstanceOfSatisfying(DomainException.class,e -> assertThat(e.getCode()).isEqualTo("USER_ALREADY_EXISTS"));
  verifyNoInteractions(provider);
 }
 @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration(exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration.class)
 @EntityScan(basePackageClasses=User.class) @EnableJpaRepositories(basePackageClasses=UserRepository.class)
 static class Config {
  @Bean KeycloakService provider() {return mock(KeycloakService.class);}
  @Bean ProvisioningService provisioning(JdbcTemplate j,UserRepository u,KeycloakService p,PlatformTransactionManager m) {return new ProvisioningService(j,u,p,m);}
  @Bean UserService service(UserRepository u,UserAddressRepository a,KeycloakService p,ProvisioningService r) {return new UserService(u,a,p,r);}
 }
}
