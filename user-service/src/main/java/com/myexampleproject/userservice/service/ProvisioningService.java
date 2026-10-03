package com.myexampleproject.userservice.service;
import com.myexampleproject.userservice.dto.UserRequest;
import com.myexampleproject.userservice.model.User;
import com.myexampleproject.userservice.repository.UserRepository;
import com.myexampleproject.common.exception.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
@Service
public class ProvisioningService {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final KeycloakService provider;
    private final TransactionTemplate tx;
    @org.springframework.beans.factory.annotation.Value("${keycloak.user-role:user}")
    private String userRole="user";
    public ProvisioningService(JdbcTemplate jdbc,UserRepository users,KeycloakService provider,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.users=users;this.provider=provider;
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public User register(UserRequest request,String idempotencyKey) {
        request.setUsername(request.getUsername().strip().toLowerCase(Locale.ROOT));
        request.setEmail(request.getEmail().strip().toLowerCase(Locale.ROOT));
        if(idempotencyKey==null || !idempotencyKey.matches("[A-Za-z0-9-]{16,64}"))
            throw new DomainException(HttpStatus.BAD_REQUEST,"IDEMPOTENCY_KEY_REQUIRED","A stable registration Idempotency-Key is required");
        String id=prepare(request,idempotencyKey);
        try {
            return tx.execute(status -> {
                var intent=jdbc.queryForMap("SELECT * FROM user_provisioning_intent WHERE intent_id=? FOR UPDATE",id);
                if(!hash(request,id).equals(intent.get("request_hash"))) throw conflict("IDEMPOTENCY_CONFLICT");
                if("COMPLETE".equals(intent.get("state"))) return users.findByKeycloakId((String)intent.get("keycloak_id"))
                    .orElseThrow(() -> retry());
                String keycloakId=provider.ensureProvisionedUser(request,id,(String)intent.get("keycloak_id"));
                jdbc.update("UPDATE user_provisioning_intent SET keycloak_id=?, state='KEYCLOAK_CREATED', updated_at=CURRENT_TIMESTAMP WHERE intent_id=?",keycloakId,id);
                // Role name follows the existing normalized role policy; only an origin-proven identity reaches here.
                provider.assignRealmRoleToUser(keycloakId,userRole);
                User user=users.findByKeycloakId(keycloakId).orElseGet(() -> User.builder().keycloakId(keycloakId)
                    .fullName(request.getFullName()).email(request.getEmail()).phoneNumber(request.getPhoneNumber())
                    .address(request.getAddress()).status(true).build());
                users.saveAndFlush(user);
                jdbc.update("UPDATE user_provisioning_intent SET state='COMPLETE',last_error_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE intent_id=?",id);
                return user;
            });
        } catch(RuntimeException ex) {
            String code=ex instanceof DomainException de ? de.getCode() : "PROVISIONING_RETRY";
            try {tx.executeWithoutResult(status -> jdbc.update("UPDATE user_provisioning_intent SET attempts=attempts+1,state=?,last_error_code=?,updated_at=CURRENT_TIMESTAMP WHERE intent_id=? AND state<>'COMPLETE'",
                code.equals("USER_ALREADY_EXISTS") ? "CONFLICT" : "RECONCILIATION_REQUIRED",code,id));} catch(RuntimeException ignored) { /* Durable original intent survives; no credentials are logged. */ }
            if(ex instanceof DomainException de) throw de;
            throw retry();
        }
    }
    private String prepare(UserRequest request,String key) {
        return tx.execute(status -> {
            var existing=jdbc.queryForList("SELECT * FROM user_provisioning_intent WHERE idempotency_key=?",key);
            if(!existing.isEmpty()) {
                String id=(String)existing.getFirst().get("intent_id");
                if(!hash(request,id).equals(existing.getFirst().get("request_hash"))) throw conflict("IDEMPOTENCY_CONFLICT");
                return id;
            }
            if(users.findByEmail(request.getEmail()).isPresent()) throw conflict("USER_ALREADY_EXISTS");
            String id=UUID.randomUUID().toString();
            try {jdbc.update("INSERT INTO user_provisioning_intent(intent_id,idempotency_key,username_key,email_key,request_hash,state,created_at,updated_at) VALUES(?,?,?,?,?,'PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id,key,request.getUsername(),request.getEmail(),hash(request,id));}
            catch(DuplicateKeyException duplicate) {
                var raced=jdbc.queryForList("SELECT * FROM user_provisioning_intent WHERE idempotency_key=? FOR UPDATE",key);
                if(raced.isEmpty()) throw conflict("USER_ALREADY_EXISTS");
                String previous=(String)raced.getFirst().get("intent_id");
                if(!hash(request,previous).equals(raced.getFirst().get("request_hash"))) throw conflict("IDEMPOTENCY_CONFLICT");
                return previous;
            }
            return id;
        });
    }
    private String hash(UserRequest request,String salt) {
        try {
            String fields=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(List.of(request.getUsername(),request.getEmail(),
                Objects.toString(request.getFullName(),""),Objects.toString(request.getPhoneNumber(),""),Objects.toString(request.getAddress(),""),request.getPassword()));
            var spec=new PBEKeySpec(fields.toCharArray(),salt.getBytes(StandardCharsets.UTF_8),110000,256);
            try {return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());}
            finally {spec.clearPassword();}
        } catch(Exception ex) {throw new IllegalStateException("Cannot fingerprint registration");}
    }
    private static DomainException conflict(String code) {return new DomainException(HttpStatus.CONFLICT,code,"Registration identity or request conflicts");}
    private static DomainException retry() {return new DomainException(HttpStatus.SERVICE_UNAVAILABLE,"PROVISIONING_RETRY","Registration incomplete; retry the same request and key");}
}
