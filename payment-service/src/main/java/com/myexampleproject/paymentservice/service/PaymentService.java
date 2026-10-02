package com.myexampleproject.paymentservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myexampleproject.common.event.OrderValidatedEvent;
import com.myexampleproject.common.event.PaymentFailedEvent;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final VNPayConfig vnPayConfig;

    @Value("${order.service.base-url:http://localhost:8086}")
    private String orderServiceBaseUrl;

    private final RestTemplate restTemplate = new RestTemplate();

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

                boolean paymentSuccess = processPayment(event);
                if (paymentSuccess) {
                    String paymentId = UUID.randomUUID().toString();
                    PaymentProcessedEvent successEvent = new PaymentProcessedEvent(event.getOrderNumber(), paymentId);
                    kafkaTemplate.send("payment-processed-topic", event.getOrderNumber(), successEvent);
                    log.info("Payment SUCCESS for Order {}. Payment ID: {}", event.getOrderNumber(), paymentId);
                } else {
                    PaymentFailedEvent failedEvent = new PaymentFailedEvent(event.getOrderNumber(), "Payment gateway declined.");
                    kafkaTemplate.send("payment-failed-topic", event.getOrderNumber(), failedEvent);
                    log.warn("Payment FAILED for Order {}. Reason: {}", event.getOrderNumber(), failedEvent.getReason());
                }
            } catch (Exception e) {
                log.error("Lỗi xử lý payment cho key {}: {}", record.key(), e.getMessage(), e);
            }
        }
    }

    private boolean processPayment(OrderValidatedEvent event) {
        log.info("Simulating payment processing for Order {}...", event.getOrderNumber());
        return true;
    }

    @Transactional
    public PaymentTransactionResponse createVnpayPayment(String requesterUserId,
                                                         CreateVnpayPaymentRequest request,
                                                         HttpServletRequest servletRequest) {
        if (request == null || request.getOrderNumber() == null || request.getOrderNumber().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "orderNumber là bắt buộc");
        }
        validateVnpayConfiguration();

        OrderPaymentContextResponse context = fetchOrderContext(request.getOrderNumber());
        validatePaymentRequester(requesterUserId, context);

        if (!"VNPAY".equalsIgnoreCase(context.getPaymentMethod())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Đơn hàng này không sử dụng phương thức thanh toán VNPAY");
        }
        if (!"VALIDATED".equalsIgnoreCase(context.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Đơn hàng chưa sẵn sàng để thanh toán trực tuyến");
        }

        Optional<PaymentTransaction> existingOpt = paymentTransactionRepository.findByOrderNumber(context.getOrderNumber());
        if (existingOpt.isPresent()) {
            PaymentTransaction existing = existingOpt.get();
            if ("SUCCESS".equalsIgnoreCase(existing.getStatus())) {
                return mapToResponse(existing);
            }
            if ("PENDING".equalsIgnoreCase(existing.getStatus())
                    && existing.getPaymentUrl() != null && !existing.getPaymentUrl().isBlank()) {
                return mapToResponse(existing);
            }
        }

        PaymentTransaction transaction = existingOpt.orElseGet(PaymentTransaction::new);
        transaction.setOrderNumber(context.getOrderNumber());
        transaction.setProvider("VNPAY");
        transaction.setStatus("PENDING");
        transaction.setAmount(context.getTotalPrice() != null ? context.getTotalPrice() : BigDecimal.ZERO);
        transaction.setTxnRef(buildTxnRef(context.getOrderNumber()));

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

    @Transactional(readOnly = true)
    public PaymentTransactionResponse getPaymentByOrderNumber(String requesterUserId, String orderNumber) {
        OrderPaymentContextResponse context = fetchOrderContext(orderNumber);
        validatePaymentRequester(requesterUserId, context);

        Optional<PaymentTransaction> transactionOpt = paymentTransactionRepository.findByOrderNumber(orderNumber);

        if (transactionOpt.isPresent()) {
            return mapToResponse(transactionOpt.get());
        }

        return PaymentTransactionResponse.builder()
                .orderNumber(orderNumber)
                .provider("VNPAY")
                .status("NOT_CREATED")
                .amount(context.getTotalPrice() != null ? context.getTotalPrice() : BigDecimal.ZERO)
                .paymentUrl(null)
                .txnRef(null)
                .gatewayMessage("Chưa tạo giao dịch thanh toán cho đơn hàng này")
                .build();
    }

    @Transactional
    public ResponseEntity<Void> handleVnpayReturn(Map<String, String> params) {
        PaymentReturnResult result = processReturn(params);
        String redirectUrl = vnPayConfig.getFrontendBaseUrl() + "/checkout/waiting/" + result.orderNumber()
                + (result.success() ? "?payment=success" : "?payment=failed");
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirectUrl)).build();
    }

    @Transactional
    public Map<String, String> handleVnpayIpn(Map<String, String> params) {
        PaymentReturnResult result = processReturn(params);
        if (result.success()) {
            return Map.of("RspCode", "00", "Message", "Confirm Success");
        }
        return Map.of("RspCode", "02", "Message", result.message());
    }

    private PaymentReturnResult processReturn(Map<String, String> rawParams) {
        Map<String, String> params = new HashMap<>(rawParams != null ? rawParams : Map.of());
        String secureHash = params.remove("vnp_SecureHash");
        params.remove("vnp_SecureHashType");

        String signValue = VNPayUtil.hmacSHA512(vnPayConfig.getSecretKey(), VNPayUtil.buildHashData(params));
        String txnRef = params.get("vnp_TxnRef");
        PaymentTransaction transaction = paymentTransactionRepository.findByTxnRef(txnRef)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy giao dịch thanh toán"));

        if (!Objects.equals(signValue, secureHash)) {
            String previousStatus = transaction.getStatus();
            if ("SUCCESS".equalsIgnoreCase(previousStatus)) {
                return new PaymentReturnResult(transaction.getOrderNumber(), true, "Already confirmed");
            }
            transaction.setStatus("FAILED");
            transaction.setGatewayMessage("Invalid secure hash");
            paymentTransactionRepository.save(transaction);
            if (!"FAILED".equalsIgnoreCase(previousStatus)) {
                kafkaTemplate.send("payment-failed-topic", transaction.getOrderNumber(),
                        new PaymentFailedEvent(transaction.getOrderNumber(), "VNPAY signature invalid"));
            }
            return new PaymentReturnResult(transaction.getOrderNumber(), false, "Invalid signature");
        }

        String responseCode = params.getOrDefault("vnp_ResponseCode", "99");
        String transactionStatus = params.getOrDefault("vnp_TransactionStatus", responseCode);
        String previousStatus = transaction.getStatus();
        transaction.setGatewayResponseCode(responseCode);
        transaction.setGatewayTransactionNo(params.get("vnp_TransactionNo"));

        if ("00".equals(responseCode) && "00".equals(transactionStatus)) {
            transaction.setStatus("SUCCESS");
            transaction.setGatewayMessage("Thanh toán thành công");
            paymentTransactionRepository.save(transaction);
            if (!"SUCCESS".equalsIgnoreCase(previousStatus)) {
                kafkaTemplate.send("payment-processed-topic", transaction.getOrderNumber(),
                        new PaymentProcessedEvent(transaction.getOrderNumber(), transaction.getTxnRef()));
            }
            return new PaymentReturnResult(transaction.getOrderNumber(), true, "OK");
        }

        if ("SUCCESS".equalsIgnoreCase(previousStatus)) {
            return new PaymentReturnResult(transaction.getOrderNumber(), true, "Already confirmed");
        }

        transaction.setStatus("FAILED");
        transaction.setGatewayMessage("Thanh toán thất bại hoặc bị hủy");
        paymentTransactionRepository.save(transaction);
        if (!"FAILED".equalsIgnoreCase(previousStatus)) {
            kafkaTemplate.send("payment-failed-topic", transaction.getOrderNumber(),
                    new PaymentFailedEvent(transaction.getOrderNumber(), "VNPAY response=" + responseCode + ", status=" + transactionStatus));
        }
        return new PaymentReturnResult(transaction.getOrderNumber(), false, "Payment failed");
    }

    private PaymentTransactionResponse mapToResponse(PaymentTransaction transaction) {
        return PaymentTransactionResponse.builder()
                .orderNumber(transaction.getOrderNumber())
                .provider(transaction.getProvider())
                .status(transaction.getStatus())
                .amount(transaction.getAmount())
                .paymentUrl(transaction.getPaymentUrl())
                .txnRef(transaction.getTxnRef())
                .gatewayMessage(transaction.getGatewayMessage())
                .build();
    }

    private Map<String, String> buildVnpayParams(PaymentTransaction transaction, HttpServletRequest request) {
        Map<String, String> params = new HashMap<>();
        params.put("vnp_Version", vnPayConfig.getVersion());
        params.put("vnp_Command", vnPayConfig.getCommand());
        params.put("vnp_TmnCode", vnPayConfig.getTmnCode());
        params.put("vnp_Amount", transaction.getAmount().multiply(BigDecimal.valueOf(100)).toBigInteger().toString());
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
        params.put("vnp_ExpireDate", now.plusMinutes(15).format(formatter));
        return params;
    }

    private String buildTxnRef(String orderNumber) {
        String compactOrder = orderNumber.replaceAll("[^A-Za-z0-9]", "");
        String suffix = String.valueOf(System.currentTimeMillis());
        String seed = compactOrder.length() > 24 ? compactOrder.substring(0, 24) : compactOrder;
        return (seed + suffix).substring(0, Math.min(seed.length() + suffix.length(), 50));
    }

    private void validatePaymentRequester(String requesterUserId, OrderPaymentContextResponse context) {
        if (requesterUserId == null || requesterUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bạn cần đăng nhập");
        }
        if (context == null || context.getUserId() == null || !requesterUserId.equals(context.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền thanh toán đơn hàng này");
        }
    }

    private OrderPaymentContextResponse fetchOrderContext(String orderNumber) {
        String url = orderServiceBaseUrl + "/api/order/internal/" + orderNumber + "/payment-context";
        return restTemplate.getForObject(url, OrderPaymentContextResponse.class);
    }

    private void validateVnpayConfiguration() {
        if (vnPayConfig.getTmnCode() == null || vnPayConfig.getTmnCode().isBlank()
                || vnPayConfig.getSecretKey() == null || vnPayConfig.getSecretKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "VNPAY chưa được cấu hình tmnCode/secretKey");
        }
    }

    private record PaymentReturnResult(String orderNumber, boolean success, String message) {}
}
