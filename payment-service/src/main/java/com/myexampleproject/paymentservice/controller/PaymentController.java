package com.myexampleproject.paymentservice.controller;

import com.myexampleproject.paymentservice.dto.CreateVnpayPaymentRequest;
import com.myexampleproject.paymentservice.dto.PaymentTransactionResponse;
import com.myexampleproject.paymentservice.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/vnpay/create")
    public PaymentTransactionResponse createVnpayPayment(@AuthenticationPrincipal Jwt jwt,
                                                         @RequestBody CreateVnpayPaymentRequest request,
                                                         HttpServletRequest servletRequest) {
        return paymentService.createVnpayPayment(jwt != null ? jwt.getSubject() : null, request, servletRequest);
    }

    @GetMapping("/order/{orderNumber}")
    public PaymentTransactionResponse getByOrderNumber(@AuthenticationPrincipal Jwt jwt,
                                                       @PathVariable String orderNumber) {
        return paymentService.getPaymentByOrderNumber(jwt != null ? jwt.getSubject() : null, orderNumber);
    }

    @GetMapping("/vnpay/return")
    public ResponseEntity<Void> vnpayReturn(HttpServletRequest request) {
        Map<String, String> params = request.getParameterMap().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue()[0]));
        return paymentService.handleVnpayReturn(params);
    }

    @GetMapping("/vnpay/ipn")
    public Map<String, String> vnpayIpn(HttpServletRequest request) {
        Map<String, String> params = request.getParameterMap().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue()[0]));
        return paymentService.handleVnpayIpn(params);
    }
}
