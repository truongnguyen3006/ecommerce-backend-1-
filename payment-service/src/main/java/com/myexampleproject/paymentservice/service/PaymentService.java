package com.myexampleproject.paymentservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myexampleproject.common.event.OrderValidatedEvent;
import com.myexampleproject.common.event.PaymentFailedEvent;
import com.myexampleproject.common.event.PaymentInvestigationEvent;
import com.myexampleproject.common.event.PaymentProcessedEvent;
import com.myexampleproject.paymentservice.config.VNPayConfig;
import com.myexampleproject.paymentservice.dto.CreateVnpayPaymentRequest;
import com.myexampleproject.paymentservice.dto.OrderPaymentContextResponse;
import com.myexampleproject.paymentservice.dto.PaymentTransactionResponse;
import com.myexampleproject.paymentservice.model.PaymentTransaction;
import com.myexampleproject.paymentservice.repository.PaymentTransactionRepository;
import com.myexampleproject.paymentservice.util.VNPayUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.annotation.KafkaListener;
import com.myexampleproject.common.outbox.JdbcOutbox;
import com.myexampleproject.common.event.OnlinePaymentReceivedEvent;
import com.myexampleproject.common.event.OnlinePaymentDecisionEvent;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.math.BigInteger;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.kafka.listener.BatchListenerFailedException;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {
    private final JdbcOutbox outbox;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final VNPayConfig vnPayConfig;

    @Value("${order.service.base-url:http://localhost:8086}")
    private String orderServiceBaseUrl;

    private final RestTemplate restTemplate;

    @KafkaListener(
            topics = "order-validated-topic",
            groupId = "payment-group",
            containerFactory = "paymentKafkaListenerContainerFactory"
    )
    public void handleOrderValidation(List<ConsumerRecord<String, Object>> records) {
        log.info("Received batch of {} validated events", records.size());

        for (ConsumerRecord<String, Object> record : records) {
            try {
                OrderValidatedEvent event = objectMapper.convertValue(record.value(), OrderValidatedEvent.class);
                log.info("Received OrderValidatedEvent for Order {}. Processing mock/COD payment...", event.getOrderNumber());

                transactions.executeWithoutResult(tx -> {
                boolean paymentSuccess = processPayment(event);
                if (paymentSuccess) {
                    String paymentId = "COD-" + event.getOrderNumber();
                    PaymentProcessedEvent successEvent = new PaymentProcessedEvent(event.getOrderNumber(), paymentId);
                    publish("payment-processed-topic", event.getOrderNumber(), successEvent);
                    log.info("Payment SUCCESS for Order {}. Payment ID: {}", event.getOrderNumber(), paymentId);
                } else {
                    PaymentFailedEvent failedEvent = new PaymentFailedEvent(event.getOrderNumber(), "Payment gateway declined.");
                    publish("payment-failed-topic", event.getOrderNumber(), failedEvent);
                    log.warn("Payment FAILED for Order {}. Reason: {}", event.getOrderNumber(), failedEvent.getReason());
                }
                });
            } catch (Exception e) {
                throw new BatchListenerFailedException("Payment event processing failed", e, record);
            }
        }
    }

    private boolean processPayment(OrderValidatedEvent event) {
        log.info("Simulating payment processing for Order {}...", event.getOrderNumber());
        return true;
    }

    @Transactional
    public PaymentTransactionResponse createVnpayPayment(String requesterUserId, String bearerToken,
                                                         CreateVnpayPaymentRequest request,
                                                         HttpServletRequest servletRequest) {
        if (request == null || request.getOrderNumber() == null || request.getOrderNumber().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "orderNumber là bắt buộc");
        }
        validateVnpayConfiguration();

        OrderPaymentContextResponse context = fetchOrderContext(request.getOrderNumber(), bearerToken);
        validatePaymentRequester(requesterUserId, context);

        Optional<PaymentTransaction> existingOpt = paymentTransactionRepository.findByOrderNumberForUpdate(context.getOrderNumber());
        PaymentTransaction transaction = existingOpt.orElseGet(PaymentTransaction::new);
        if (existingOpt.isPresent()) {
            expireAttempt(transaction, LocalDateTime.now());
            if (!"PENDING".equals(transaction.getStatus())) return mapToResponse(transaction);
        }
        if (!"VNPAY".equalsIgnoreCase(context.getPaymentMethod())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Đơn hàng này không sử dụng phương thức thanh toán VNPAY");
        }
        if (!"VALIDATED".equalsIgnoreCase(context.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Đơn hàng chưa sẵn sàng để thanh toán trực tuyến");
        }
        if (context.getTotalPrice() == null || context.getTotalPrice().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order amount must be positive for online payment");
        }
        try { context.getTotalPrice().multiply(BigDecimal.valueOf(100)).toBigIntegerExact(); }
        catch (ArithmeticException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Order amount has unsupported precision"); }

        transaction.setOrderNumber(context.getOrderNumber());
        transaction.setProvider("VNPAY");
        transaction.setStatus("PENDING");
        transaction.setAmount(context.getTotalPrice());
        if (transaction.getTxnRef() == null) transaction.setTxnRef(buildTxnRef(context.getOrderNumber()));
        // Order owns the cancellation decision. This separate committed fence is conservative if our SQL later fails.
        // Retrying creation reuses the same reference; it never clears the fence or releases stock on uncertainty.
        beginOrderPayment(context.getOrderNumber(), transaction.getTxnRef(), bearerToken);
        if (transaction.getPaymentUrl() != null && !transaction.getPaymentUrl().isBlank()) return mapToResponse(transaction);

        transaction.setExpiresAt(LocalDateTime.now().plusMinutes(15));
        Map<String, String> params = buildVnpayParams(transaction, servletRequest);
        String hashData = VNPayUtil.buildHashData(params);
        String secureHash = VNPayUtil.hmacSHA512(vnPayConfig.getSecretKey(), hashData);
        params.put("vnp_SecureHash", secureHash);
        params.put("vnp_SecureHashType", "HmacSHA512");

        String paymentUrl = vnPayConfig.getApiUrl() + "?" + VNPayUtil.buildQuery(params);
        transaction.setPaymentUrl(paymentUrl);
        paymentTransactionRepository.save(transaction);
        return mapToResponse(transaction);
    }

    @Transactional
    public PaymentTransactionResponse getPaymentByOrderNumber(String requesterUserId, String bearerToken, String orderNumber) {
        OrderPaymentContextResponse context = fetchOrderContext(orderNumber, bearerToken);
        validatePaymentRequester(requesterUserId, context);

        Optional<PaymentTransaction> transactionOpt = paymentTransactionRepository.findByOrderNumberForUpdate(orderNumber);

        if (transactionOpt.isPresent()) {
            expireAttempt(transactionOpt.get(), LocalDateTime.now());
            return mapToResponse(transactionOpt.get());
        }

        return PaymentTransactionResponse.builder()
                .orderNumber(orderNumber)
                .provider("VNPAY")
                .status("NOT_CREATED")
                .retryAvailable("VALIDATED".equals(context.getStatus()))
                .amount(context.getTotalPrice() != null ? context.getTotalPrice() : BigDecimal.ZERO)
                .paymentUrl(null)
                .txnRef(null)
                .gatewayMessage("Chưa tạo giao dịch thanh toán cho đơn hàng này")
                .build();
    }

    @Transactional(readOnly = true)
    public ResponseEntity<Void> handleVnpayReturn(Map<String, String> params) {
        // Browser navigation validates integrity and identity, but never records a receipt or publishes events.
        Map<String,String> verified = verifyCallback(params);
        PaymentTransaction payment = paymentTransactionRepository.findByTxnRef(verified.get("vnp_TxnRef"))
                .orElseThrow(() -> new CallbackRejected("01","Order not found"));
        verifyAmount(verified,payment);
        String redirectUrl = vnPayConfig.getFrontendBaseUrl() + "/checkout/waiting/"
                + java.net.URLEncoder.encode(payment.getOrderNumber(),StandardCharsets.UTF_8) + "?payment=waiting";
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirectUrl)).build();
    }

    @Transactional
    public Map<String, String> handleVnpayIpn(Map<String, String> rawParams) {
        try {
            Map<String,String> params = verifyCallback(rawParams);
            PaymentTransaction transaction = paymentTransactionRepository.findByTxnRefForUpdate(params.get("vnp_TxnRef"))
                    .orElseThrow(() -> new CallbackRejected("01","Order not found"));
            verifyAmount(params,transaction);
            boolean success = "00".equals(params.get("vnp_ResponseCode")) && "00".equals(params.get("vnp_TransactionStatus"));
            if (transaction.isProviderSuccessReceived() || "SUCCESS".equals(transaction.getStatus())
                    || ("FAILED".equals(transaction.getStatus()) && !success)) return ack("02","Order already confirmed");
            if (!Set.of("PENDING","FAILED","EXPIRED_RECONCILIATION_REQUIRED").contains(transaction.getStatus()))
                return ack("02","Order already confirmed");
            transaction.setGatewayResponseCode(params.get("vnp_ResponseCode"));
            transaction.setGatewayTransactionNo(params.get("vnp_TransactionNo"));
            transaction.setLastCallbackAt(LocalDateTime.now());
            transaction.setStatus(success ? "SUCCESS_PENDING_ORDER" : "FAILED");
            transaction.setProviderSuccessReceived(success);
            transaction.setGatewayMessage(success ? "Đã ghi nhận tiền; đang xác nhận quyết định đơn hàng" : "Thanh toán thất bại hoặc bị hủy");
            paymentTransactionRepository.save(transaction);
            if (success) publish("online-payment-received-topic",transaction.getOrderNumber(),
                    new OnlinePaymentReceivedEvent(transaction.getOrderNumber(),transaction.getTxnRef(),transaction.getAmount()));
            else publish("payment-failed-topic",transaction.getOrderNumber(),
                    new PaymentFailedEvent(transaction.getOrderNumber(),"VNPAY response="+params.get("vnp_ResponseCode"),transaction.getTxnRef()));
            // A verified failure is also an accepted, durably recorded notification.
            return ack("00","Confirm Success");
        } catch (CallbackRejected rejected) {return ack(rejected.code,rejected.getReason());}
    }

    private Map<String,String> verifyCallback(Map<String,String> rawParams) {
        validateVnpayConfiguration();
        Map<String,String> params = new HashMap<>(rawParams == null ? Map.of() : rawParams);
        String secureHash = params.remove("vnp_SecureHash");params.remove("vnp_SecureHashType");
        String expected = VNPayUtil.hmacSHA512(vnPayConfig.getSecretKey(), VNPayUtil.buildHashData(params));
        if (secureHash == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),secureHash.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII))
                || !vnPayConfig.getTmnCode().equals(params.get("vnp_TmnCode"))) throw new CallbackRejected("97","Invalid signature or merchant");
        if (params.get("vnp_TxnRef") == null || params.get("vnp_TxnRef").isBlank()) throw new CallbackRejected("01","Order not found");
        if (!params.getOrDefault("vnp_ResponseCode", "").matches("[0-9]{2}")
                || !params.getOrDefault("vnp_TransactionStatus", "").matches("[0-9]{2}")) throw new CallbackRejected("99","Invalid request");
        return params;
    }

    private void verifyAmount(Map<String,String> params, PaymentTransaction payment) {
        try {
            if (!new BigInteger(params.getOrDefault("vnp_Amount","-1")).equals(payment.getAmount().multiply(BigDecimal.valueOf(100)).toBigIntegerExact()))
                throw new CallbackRejected("04","Invalid amount");
        } catch (NumberFormatException | ArithmeticException ex) {throw new CallbackRejected("04","Invalid amount");}
    }
    private Map<String,String> ack(String code,String message) {return Map.of("RspCode",code,"Message",message);}
    private static class CallbackRejected extends ResponseStatusException {
        private final String code;
        CallbackRejected(String code,String message) {super(HttpStatus.BAD_REQUEST,message);this.code=code;}
    }

    private void expireAttempt(PaymentTransaction payment, LocalDateTime now) {
        if (!"PENDING".equals(payment.getStatus()) || payment.getPaymentUrl() == null) return;
        if (payment.getExpiresAt() != null && payment.getExpiresAt().isAfter(now)) return;
        payment.setStatus("EXPIRED_RECONCILIATION_REQUIRED");payment.setPaymentUrl(null);
        payment.setGatewayMessage("Liên kết đã hết hạn; cần đối soát trước khi thử lại. Vui lòng liên hệ hỗ trợ.");
        paymentTransactionRepository.save(payment);
        publish("payment-investigation-topic",payment.getOrderNumber(),new PaymentInvestigationEvent(payment.getOrderNumber(),payment.getTxnRef(),"PAYMENT_ATTEMPT_EXPIRED"));
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${app.payment.recovery.scan-ms:30000}")
    public void recoverPayments() {
        LocalDateTime now=LocalDateTime.now();
        for (String ref : paymentTransactionRepository.findRecoveryCandidates(now,now.minusMinutes(5),org.springframework.data.domain.PageRequest.of(0,20))) recoverPayment(ref,now);
    }
    public void recoverPayment(String ref,LocalDateTime now) {
        transactions.executeWithoutResult(tx -> {
            PaymentTransaction payment=paymentTransactionRepository.findByTxnRefForUpdate(ref).orElseThrow();
            expireAttempt(payment,now);
            if (!"SUCCESS_PENDING_ORDER".equals(payment.getStatus()) || (payment.getRecoveryNextAt()!=null && payment.getRecoveryNextAt().isAfter(now))) return;
            if (payment.getRecoveryAttempts() >= 5) {
                payment.setStatus("RECONCILIATION_REQUIRED");payment.setOrderDecisionReason("ORDER_DECISION_RETRIES_EXHAUSTED");
                publish("payment-investigation-topic",payment.getOrderNumber(),new PaymentInvestigationEvent(payment.getOrderNumber(),ref,"ORDER_DECISION_RETRIES_EXHAUSTED"));
            } else {
                publish("online-payment-received-topic",payment.getOrderNumber(),new OnlinePaymentReceivedEvent(payment.getOrderNumber(),ref,payment.getAmount()));
                payment.setRecoveryAttempts(payment.getRecoveryAttempts()+1);
                payment.setRecoveryNextAt(now.plusSeconds(Math.min(300,10L << payment.getRecoveryAttempts())));
            }
            paymentTransactionRepository.save(payment);
        });
    }

    @KafkaListener(topics="online-payment-decision-topic", groupId="payment-order-decision-group",
            containerFactory="onlinePaymentDecisionKafkaListenerContainerFactory")
    public void handleOnlinePaymentDecisions(List<ConsumerRecord<String,Object>> records) {
        for (var record : records) try {
            transactions.executeWithoutResult(tx -> {
                OnlinePaymentDecisionEvent decision = objectMapper.convertValue(record.value(), OnlinePaymentDecisionEvent.class);
                PaymentTransaction payment = paymentTransactionRepository.findByTxnRefForUpdate(decision.getTxnRef()).orElseThrow();
                if (!decision.getOrderNumber().equals(payment.getOrderNumber())) throw new IllegalArgumentException("Payment decision reference mismatch");
                if (!payment.isProviderSuccessReceived() && !"SUCCESS".equals(payment.getStatus()))
                    throw new IllegalStateException("Payment receipt is not committed yet");
                if ("RECONCILIATION_REQUIRED".equals(payment.getStatus())) return;
                if (!decision.isAccepted()) {
                    payment.setStatus("RECONCILIATION_REQUIRED");payment.setOrderDecisionReason(decision.getReason());
                    payment.setGatewayMessage("Đã ghi nhận tiền; đơn hàng cần đối soát. Vui lòng liên hệ hỗ trợ.");
                    paymentTransactionRepository.save(payment);
                    log.error("Payment reconciliation required order={} reference={}", payment.getOrderNumber(), payment.getTxnRef());
                } else if ("SUCCESS_PENDING_ORDER".equals(payment.getStatus())) {
                    payment.setStatus("SUCCESS");payment.setOrderDecisionReason("ACCEPTED");payment.setGatewayMessage("Thanh toán và đơn hàng đã được xác nhận");
                    paymentTransactionRepository.save(payment);
                    publish("payment-processed-topic", payment.getOrderNumber(), new PaymentProcessedEvent(payment.getOrderNumber(), payment.getTxnRef()));
                }
            });
        } catch (Exception ex) { throw new BatchListenerFailedException("Online payment decision failed", ex, record); }
    }

    private PaymentTransactionResponse mapToResponse(PaymentTransaction transaction) {
        return PaymentTransactionResponse.builder()
                .orderNumber(transaction.getOrderNumber())
                .provider(transaction.getProvider())
                .status(transaction.getStatus())
                .amount(transaction.getAmount())
                .paymentUrl("PENDING".equals(transaction.getStatus()) ? transaction.getPaymentUrl() : null)
                .expiresAt(transaction.getExpiresAt())
                .retryAvailable(false)
                .txnRef(transaction.getTxnRef())
                .gatewayMessage(transaction.getGatewayMessage())
                .providerSuccessReceived(transaction.isProviderSuccessReceived() || "SUCCESS".equals(transaction.getStatus()))
                .build();
    }

    private Map<String, String> buildVnpayParams(PaymentTransaction transaction, HttpServletRequest request) {
        Map<String, String> params = new HashMap<>();
        params.put("vnp_Version", vnPayConfig.getVersion());
        params.put("vnp_Command", vnPayConfig.getCommand());
        params.put("vnp_TmnCode", vnPayConfig.getTmnCode());
        params.put("vnp_Amount", transaction.getAmount().multiply(BigDecimal.valueOf(100)).toBigIntegerExact().toString());
        params.put("vnp_CurrCode", "VND");
        params.put("vnp_TxnRef", transaction.getTxnRef());
        params.put("vnp_OrderInfo", "Thanh toan don hang " + transaction.getOrderNumber());
        params.put("vnp_OrderType", vnPayConfig.getOrderType());
        params.put("vnp_Locale", "vn");
        params.put("vnp_ReturnUrl", vnPayConfig.getReturnUrl());
        params.put("vnp_IpAddr", request != null ? VNPayUtil.getIpAddress(request) : "127.0.0.1");

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        LocalDateTime now = LocalDateTime.now();
        params.put("vnp_CreateDate", now.format(formatter));
        params.put("vnp_ExpireDate", transaction.getExpiresAt().format(formatter));
        return params;
    }

    private String buildTxnRef(String orderNumber) {
        return "VNP-" + UUID.nameUUIDFromBytes(orderNumber.getBytes(StandardCharsets.UTF_8));
    }

    private void beginOrderPayment(String orderNumber, String txnRef, String bearerToken) {
        HttpHeaders headers = new HttpHeaders();headers.setBearerAuth(bearerToken);
        try {
            restTemplate.exchange(orderServiceBaseUrl + "/api/order/internal/{orderNumber}/payment-attempt", HttpMethod.POST,
                    new HttpEntity<>(Map.of("txnRef", txnRef), headers), Void.class, orderNumber);
        } catch (HttpClientErrorException ex) {
            throw new ResponseStatusException(ex.getStatusCode(), "Order cannot start this payment attempt");
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Order payment decision unavailable");
        }
    }

    private void validatePaymentRequester(String requesterUserId, OrderPaymentContextResponse context) {
        if (requesterUserId == null || requesterUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bạn cần đăng nhập");
        }
        if (context == null || context.getUserId() == null || !requesterUserId.equals(context.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền thanh toán đơn hàng này");
        }
    }

    private OrderPaymentContextResponse fetchOrderContext(String orderNumber, String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        HttpHeaders headers = new HttpHeaders();headers.setBearerAuth(bearerToken);
        try {
            return restTemplate.exchange(orderServiceBaseUrl + "/api/order/internal/{orderNumber}/payment-context", HttpMethod.GET,
                    new HttpEntity<>(headers), OrderPaymentContextResponse.class, orderNumber).getBody();
        } catch (HttpClientErrorException ex) {
            throw new ResponseStatusException(ex.getStatusCode(), "Order access denied or order not found");
        } catch (RestClientException ex) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Order service unavailable"); }
    }

    private void publish(String topic, String key, Object event) {
        outbox.append(topic, key, event);
    }

    private void validateVnpayConfiguration() {
        if (vnPayConfig.getTmnCode() == null || vnPayConfig.getTmnCode().isBlank()
                || vnPayConfig.getSecretKey() == null || vnPayConfig.getSecretKey().isBlank()) {
            throw new com.myexampleproject.common.exception.DomainException(HttpStatus.CONFLICT,"PAYMENT_NOT_CONFIGURED","Online payment is not configured");
        }
    }


}
