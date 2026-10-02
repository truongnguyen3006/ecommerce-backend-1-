package com.myexampleproject.notificationservice;

import com.myexampleproject.notificationservice.config.WebSocketConfig;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSocketOwnershipTests {
    JwtDecoder decoder=mock(JwtDecoder.class);RestTemplate client=mock(RestTemplate.class);
    ChannelInterceptor interceptor;
    @BeforeEach void setup() {
        CapturingRegistration registration=new CapturingRegistration();
        new WebSocketConfig(decoder,client,"http://localhost:3001","http://order").configureClientInboundChannel(registration);
        interceptor=registration.first();
    }
    @Test void anonymousConnectIsRejected() {assertThatThrownBy(() -> send(StompHeaderAccessor.create(StompCommand.CONNECT))).isInstanceOf(MessagingException.class);}
    @Test void connectValidatesBearerJwt() {
        Jwt jwt=jwt();when(decoder.decode("test-token")).thenReturn(jwt);
        StompHeaderAccessor headers=StompHeaderAccessor.create(StompCommand.CONNECT);headers.setNativeHeader("Authorization","Bearer test-token");
        send(headers);assertThat(headers.getUser()).isInstanceOf(JwtAuthenticationToken.class);verify(decoder).decode("test-token");
    }
    @Test void anotherUsersOrderSubscriptionIsRejected() {
        when(decoder.decode("test-token")).thenReturn(jwt());
        when(client.exchange(eq("http://order/api/order/internal/{order}/payment-context"),eq(HttpMethod.GET),any(HttpEntity.class),eq(String.class),eq("O")))
                .thenThrow(new HttpClientErrorException(HttpStatus.FORBIDDEN));
        StompHeaderAccessor headers=StompHeaderAccessor.create(StompCommand.SUBSCRIBE);headers.setUser(new JwtAuthenticationToken(jwt()));headers.setDestination("/topic/order/O");
        assertThatThrownBy(() -> send(headers)).isInstanceOf(MessagingException.class);
    }
    @Test void wildcardSubscriptionAndClientPublishingAreRejected() {
        when(decoder.decode("test-token")).thenReturn(jwt());
        StompHeaderAccessor headers=StompHeaderAccessor.create(StompCommand.SUBSCRIBE);headers.setUser(new JwtAuthenticationToken(jwt()));headers.setDestination("/topic/order/*");
        assertThatThrownBy(() -> send(headers)).isInstanceOf(MessagingException.class);verifyNoInteractions(client);
        assertThatThrownBy(() -> send(StompHeaderAccessor.create(StompCommand.SEND))).isInstanceOf(MessagingException.class);
    }
    private Jwt jwt() {return Jwt.withTokenValue("test-token").header("alg","RS256").subject("A").build();}
    private void send(StompHeaderAccessor headers) {headers.setLeaveMutable(true);interceptor.preSend(MessageBuilder.createMessage(new byte[0],headers.getMessageHeaders()),mock(MessageChannel.class));}
    private static class CapturingRegistration extends ChannelRegistration {
        ChannelInterceptor first() {return getInterceptors().getFirst();}
    }
}
