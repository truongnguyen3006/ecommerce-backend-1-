package com.myexampleproject.paymentservice;

import com.myexampleproject.paymentservice.service.PaymentService;
import com.myexampleproject.paymentservice.repository.PaymentTransactionRepository;
import com.myexampleproject.paymentservice.model.PaymentTransaction;
import com.myexampleproject.paymentservice.dto.*;
import com.myexampleproject.paymentservice.config.VNPayConfig;
import com.myexampleproject.common.outbox.JdbcOutbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class PaymentAttemptTests {
    PaymentTransactionRepository repository=mock(PaymentTransactionRepository.class);
    RestTemplate rest=mock(RestTemplate.class);
    VNPayConfig config=mock(VNPayConfig.class);
    JdbcOutbox outbox=mock(JdbcOutbox.class);
    PaymentService service=new PaymentService(outbox,mock(TransactionTemplate.class),new ObjectMapper(),repository,config,rest);
    PaymentTransaction payment;
    @BeforeEach void setup() {
        when(config.getTmnCode()).thenReturn("TEST");when(config.getSecretKey()).thenReturn("test-only-key");
        when(config.getApiUrl()).thenReturn("https://provider.test/pay");when(config.getReturnUrl()).thenReturn("https://merchant.test/return");
        when(config.getVersion()).thenReturn("2.1.0");when(config.getCommand()).thenReturn("pay");when(config.getOrderType()).thenReturn("other");
        OrderPaymentContextResponse context=new OrderPaymentContextResponse();context.setUserId("OWNER");context.setOrderNumber("O");context.setStatus("VALIDATED");context.setPaymentMethod("VNPAY");context.setTotalPrice(BigDecimal.TEN);
        when(rest.exchange(anyString(),eq(HttpMethod.GET),any(HttpEntity.class),eq(OrderPaymentContextResponse.class),eq("O"))).thenReturn(ResponseEntity.ok(context));
        when(repository.findByOrderNumberForUpdate("O")).thenAnswer(a -> Optional.ofNullable(payment));
        when(repository.save(any())).thenAnswer(a -> {payment=a.getArgument(0);return payment;});
    }
    PaymentTransactionResponse create() {return service.createVnpayPayment("OWNER","fixture-token",new CreateVnpayPaymentRequest("O"),null);}
    @Test void repeatedActiveRequestsReuseReferenceUrlAndExpiry() {
        var first=create();var second=create();assertThat(first.getPaymentUrl()).isEqualTo(second.getPaymentUrl());
        assertThat(first.getTxnRef()).isEqualTo(second.getTxnRef());assertThat(first.getExpiresAt()).isEqualTo(second.getExpiresAt());
        verify(repository,times(1)).save(any());
    }
    @Test void expiredAttemptCannotRotateReferenceOrClearOrderFence() {
        var first=create();payment.setExpiresAt(LocalDateTime.now().minusSeconds(1));clearInvocations(rest);
        var response=create();assertThat(response.getStatus()).isEqualTo("EXPIRED_RECONCILIATION_REQUIRED");
        assertThat(response.getPaymentUrl()).isNull();assertThat(response.isRetryAvailable()).isFalse();assertThat(response.getTxnRef()).isEqualTo(first.getTxnRef());
        create();verify(rest,never()).exchange(anyString(),eq(HttpMethod.POST),any(HttpEntity.class),eq(Void.class),eq("O"));
        verify(outbox,times(1)).append(eq("payment-investigation-topic"),eq("O"),any());
    }
    @Test void successfulReceiptAndReconciliationAreNeverReplacedByPending() {
        create();payment.setStatus("SUCCESS");payment.setProviderSuccessReceived(true);assertThat(create().getStatus()).isEqualTo("SUCCESS");
        payment.setStatus("RECONCILIATION_REQUIRED");assertThat(create().getStatus()).isEqualTo("RECONCILIATION_REQUIRED");assertThat(create().getPaymentUrl()).isNull();
    }
}
