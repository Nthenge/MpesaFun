package com.heritage.payment_service.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heritage.payment_service.entity.MpesaTransaction;
import com.heritage.payment_service.entity.Payment;
import com.heritage.payment_service.repository.MpesaTransactionRepository;
import com.heritage.payment_service.repository.PaymentRepository;
import com.heritage.payment_service.service.DigitaxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DigitaxServiceImpl implements DigitaxService {

    private final OkHttpClient okHttpClient;
    private final ObjectMapper mapper;
    private final PaymentRepository paymentRepository;
    private final MpesaTransactionRepository mpesaTransactionRepository;

    @Value("${digitax.api.url}")
    private String digitaxApiUrl;

    @Value("${digitax.api.key}")
    private String digitaxApiKey;

    @Override
    public void reportTransaction(MpesaTransaction mpesaTransaction) {
        Payment payment = paymentRepository.findByTransactionId(mpesaTransaction.getTransactionId())
                .orElse(new Payment());

        if (payment.getPaymentMethod() == null) payment.setPaymentMethod("MPESA");
        payment.setAmount(mpesaTransaction.getAmount());

        reportTransaction(payment, mpesaTransaction.getTransactionId(), mpesaTransaction.getPhoneNumber());
    }

    private String getDigitaxPaymentCode(String method) {
        if (method == null) return "07"; // Default to 'Other' if null

        return switch (method.toUpperCase()) {
            case "MPESA", "STK_PUSH", "C2B", "AIRTEL_MONEY" -> "06"; // Mobile Money
            case "CARD", "VISA", "MASTERCARD" -> "05";               // Debit/Credit Card
            case "HERITAGE_ACCOUNT", "BANK_TRANSFER" -> "04";        // Bank Transfer/Check
            case "CASH" -> "01";                                     // Cash
            default -> "07";                                         // Other
        };
    }

    @Override
    public void reportTransaction(Payment payment, String transactionId, String phoneNumber) {

        if (transactionId == null || transactionId.isBlank()) {
            log.warn("Skipping Digitax report — transactionId is null for paymentId={}", payment.getId());
            return;
        }

        try {
            double totalAmount = payment.getAmount();

            if (totalAmount <= 0) {
                log.warn("Skipping Digitax report — invalid amount={} for transactionId={}", totalAmount, transactionId);
                return;
            }

            long timestamp = System.currentTimeMillis();

            Map<String, Object> item = new HashMap<>();
            item.put("id", "item_" + transactionId + "_" + timestamp);
            item.put("quantity", 1);
            item.put("unit_price", totalAmount);
            item.put("total_amount", totalAmount);
            item.put("item_description", payment.getPaymentMethod() + " Payment: " + transactionId);
            item.put("tax_type_code", "A");

            Map<String, Object> payload = new HashMap<>();
            payload.put("sale_date", java.time.LocalDate.now().toString());
            payload.put("trader_invoice_number", transactionId + "_" + timestamp);
            payload.put("customer_phone_number", (phoneNumber != null) ? phoneNumber : "0000000000");
            payload.put("customer_name", (payment.getCustomerName() != null) ? payment.getCustomerName() : "Walk-in Customer");
            payload.put("payment_type_code", getDigitaxPaymentCode(payment.getPaymentMethod()));
            payload.put("receipt_type_code", "S");
            payload.put("invoice_status_code", "1");
            payload.put("items", java.util.List.of(item));

            String json = mapper.writeValueAsString(payload);
            RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));

            Request request = new Request.Builder()
                    .url(digitaxApiUrl + "/ke/v2/sales")
                    .post(body)
                    .addHeader("X-API-Key", digitaxApiKey)
                    .addHeader("Content-Type", "application/json")
                    .build();

            log.info("Reporting to Digitax | transactionId={} | amount={}", transactionId, totalAmount);

            try (Response response = okHttpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "{}";

                if (response.isSuccessful()) {
                    JsonNode root = mapper.readTree(responseBody);
                    String kraReceiptNumber = root.path("data").path("receipt_number").asText();

                    if (!kraReceiptNumber.isEmpty()) {
                        payment.setKraReceiptNumber(kraReceiptNumber);
                        log.info("KRA Success [{}]: receipt={}", payment.getPaymentMethod(), kraReceiptNumber);
                    }

                    // lookup MpesaTransaction directly by transactionId
                    mpesaTransactionRepository.findByTransactionId(transactionId).ifPresent(mpesa -> {
                        mpesa.setReportedToDigitax(true);
                        mpesaTransactionRepository.save(mpesa);
                        log.info("Transaction {} marked as reported to Digitax", transactionId);
                    });

                } else {
                    log.error("KRA Rejected [{}]: {}", payment.getPaymentMethod(), responseBody);

                    // 412 = Digitax already has this transaction, stop retrying
                    if (response.code() == 412) {
                        log.warn("Digitax already has transactionId={}, marking as reported to stop retries", transactionId);
                        mpesaTransactionRepository.findByTransactionId(transactionId).ifPresent(mpesa -> {
                            mpesa.setReportedToDigitax(true);
                            mpesaTransactionRepository.save(mpesa);
                        });
                    }
                }

                paymentRepository.save(payment);
            }

        } catch (Exception e) {
            log.error("Network Error reporting to DigiTax for transactionId={}: {}", transactionId, e.getMessage());
        }
    }
}