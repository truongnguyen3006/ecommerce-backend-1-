package com.myexampleproject.paymentservice;
import com.myexampleproject.paymentservice.service.PaymentService;
import com.myexampleproject.paymentservice.repository.PaymentTransactionRepository;
import com.myexampleproject.paymentservice.model.PaymentTransaction;
import com.myexampleproject.paymentservice.config.VNPayConfig;
import com.myexampleproject.paymentservice.util.VNPayUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import com.myexampleproject.common.outbox.JdbcOutbox;
import org.springframework.transaction.support.TransactionTemplate;
import com.myexampleproject.common.event.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import java.util.function.Consumer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class PaymentCallbackTests {
    PaymentTransactionRepository repository=mock(PaymentTransactionRepository.class);
    JdbcOutbox kafka=mock(JdbcOutbox.class);
    TransactionTemplate transactions=mock(TransactionTemplate.class);
    VNPayConfig config=mock(VNPayConfig.class);
    PaymentService service=new PaymentService(kafka,transactions,new ObjectMapper(),repository,config,mock(RestTemplate.class));
    PaymentTransaction payment;
    @BeforeEach void setup() {
        when(config.getTmnCode()).thenReturn("DEMO");when(config.getSecretKey()).thenReturn("test-only-signing-key");
        payment=PaymentTransaction.builder().orderNumber("O").txnRef("TXN").amount(BigDecimal.valueOf(100)).status("PENDING").provider("VNPAY").build();
        when(repository.findByTxnRefForUpdate("TXN")).thenReturn(Optional.of(payment));
        doAnswer(a -> {((Consumer)a.getArgument(0)).accept(null);return null;}).when(transactions).executeWithoutResult(any());
    }
    Map<String,String> callback(String amount,String response) {
        Map<String,String> p=new HashMap<>(Map.of("vnp_TxnRef","TXN","vnp_TmnCode","DEMO","vnp_Amount",amount,"vnp_ResponseCode",response,"vnp_TransactionStatus",response));
        p.put("vnp_SecureHash",VNPayUtil.hmacSHA512("test-only-signing-key",VNPayUtil.buildHashData(p)));return p;
    }
    @Test void invalidSignatureDoesNotMutatePaymentOrPublishFailure() {
        Map<String,String> p=callback("10000","00");p.put("vnp_SecureHash","invalid");
        assertThatThrownBy(() -> service.handleVnpayIpn(p)).isInstanceOf(ResponseStatusException.class);assertThat(payment.getStatus()).isEqualTo("PENDING");verifyNoInteractions(kafka);verify(repository,never()).save(any());
    }
    @Test void signedWrongAmountIsRejectedWithoutMutation() {assertThatThrownBy(() -> service.handleVnpayIpn(callback("1","00"))).isInstanceOf(ResponseStatusException.class);assertThat(payment.getStatus()).isEqualTo("PENDING");verifyNoInteractions(kafka);}
    @Test void validRepeatedSuccessPublishesOnce() {service.handleVnpayIpn(callback("10000","00"));service.handleVnpayIpn(callback("10000","00"));assertThat(payment.getStatus()).isEqualTo("SUCCESS_PENDING_ORDER");assertThat(payment.isProviderSuccessReceived()).isTrue();verify(kafka,times(1)).append(eq("online-payment-received-topic"),eq("O"),any());}
    @Test void failureAfterSuccessCannotReversePayment() {service.handleVnpayIpn(callback("10000","00"));service.handleVnpayIpn(callback("10000","24"));assertThat(payment.getStatus()).isEqualTo("SUCCESS_PENDING_ORDER");verify(kafka,never()).append(eq("payment-failed-topic"),anyString(),any());}
    @Test void successAfterFailureRequiresReconciliation() {service.handleVnpayIpn(callback("10000","24"));service.handleVnpayIpn(callback("10000","00"));assertThat(payment.getStatus()).isEqualTo("SUCCESS_PENDING_ORDER");assertThat(payment.isProviderSuccessReceived()).isTrue();verify(kafka).append(eq("online-payment-received-topic"),eq("O"),any());}
    void decision(boolean accepted) {
        service.handleOnlinePaymentDecisions(List.of(new ConsumerRecord<>("online-payment-decision-topic",0,1,"O",
                new OnlinePaymentDecisionEvent("O","TXN",accepted,"ORDER_ALLOCATION_INCOMPATIBLE"))));
    }
    @Test void receiptOnlyBecomesSuccessAfterOrderAcceptsAndDuplicateDecisionDoesNotRepublish() {
        service.handleVnpayIpn(callback("10000","00"));decision(true);decision(true);
        assertThat(payment.getStatus()).isEqualTo("SUCCESS");verify(kafka,times(1)).append(eq("payment-processed-topic"),eq("O"),any());
    }
    @Test void rejectedOrderDecisionDurablyAccountsForMoneyAndNeverEmitsOrdinarySuccess() {
        service.handleVnpayIpn(callback("10000","00"));decision(false);decision(false);
        assertThat(payment.getStatus()).isEqualTo("RECONCILIATION_REQUIRED");assertThat(payment.isProviderSuccessReceived()).isTrue();
        verify(kafka,never()).append(eq("payment-processed-topic"),anyString(),any());
        service.handleVnpayIpn(callback("10000","00"));assertThat(payment.getStatus()).isEqualTo("RECONCILIATION_REQUIRED");
    }
    @Test void duplicateFailureDoesNotRepublish() {
        service.handleVnpayIpn(callback("10000","24"));service.handleVnpayIpn(callback("10000","24"));
        assertThat(payment.getStatus()).isEqualTo("FAILED");verify(kafka,times(1)).append(eq("payment-failed-topic"),eq("O"),any());
    }
}
