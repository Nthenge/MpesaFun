package com.heritage.payment_service.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heritage.payment_service.dto.request.StkPushRequest;
import com.heritage.payment_service.dto.response.MpesaResponse;
import com.heritage.payment_service.entity.MpesaTransaction;
import com.heritage.payment_service.entity.Payment;
import com.heritage.payment_service.repository.MpesaTransactionRepository;
import com.heritage.payment_service.repository.PaymentRepository;
import com.heritage.payment_service.service.DigitaxService;
import com.heritage.payment_service.service.MpesaService;
import com.heritage.payment_service.utils.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class MpesaServiceImpl implements MpesaService {

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper mapper;
    private final MpesaTransactionRepository mpesaRepository;
    private final PaymentRepository paymentRepository;
    private final DigitaxService digitaxService;
    private final JwtUtil jwtUtil;

    @Value("${mpesa.consumer.key}")
    private String consumerKey;
    @Value("${mpesa.consumer.secret}")
    private String consumerSecret;
    @Value("${mpesa.passkey}")
    private String passkey;
    @Value("${mpesa.auth.url}")
    private String authUrl;
    @Value("${mpesa.shortcode}")
    private String shortCode;
    @Value("${mpesa.c2b.shortcode}")
    private String c2bShortCode;
    @Value("${mpesa.callback.url}")
    private String callbackUrl;
    @Value("${mpesa.callback.base-url}")
    private String callbackBaseUrl;

    private String validateAndNormalizePhone(String phone) {
        if (phone == null || phone.isBlank()) throw new IllegalArgumentException("Phone cannot be empty");
        String cleaned = phone.replaceAll("[^0-9]", "");
        if (cleaned.startsWith("0")) cleaned = "254" + cleaned.substring(1);
        else if (cleaned.startsWith("7") || cleaned.startsWith("1")) cleaned = "254" + cleaned;
        if (!cleaned.matches("^254[17][0-9]{8}$")) throw new IllegalArgumentException("Invalid Kenyan phone number");
        return cleaned;
    }

    @Override
    public String getAccessToken() {
        String authString = consumerKey + ":" + consumerSecret;
        String encodedAuth = Base64.getEncoder().encodeToString(authString.getBytes());
        Request request = new Request.Builder()
                .url(authUrl)
                .get()
                .addHeader("Authorization", "Basic " + encodedAuth)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                JsonNode root = mapper.readTree(response.body().string());
                return root.get("access_token").asText();
            }
        } catch (IOException e) {
            log.error("Error fetching M-Pesa access token: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public MpesaResponse initiateStkPush(String phone, String amount) throws IOException {
        String formattedPhone = validateAndNormalizePhone(phone);
        String token = getAccessToken();
        if (token == null) throw new RuntimeException("Failed to generate Access Token.");

        String timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        String password = Base64.getEncoder().encodeToString((shortCode.trim() + passkey.trim() + timestamp).getBytes());

        log.info("Step 3: ShortCode={}, CallbackURL={}", shortCode, callbackUrl);

        StkPushRequest stkRequest = new StkPushRequest();
        stkRequest.setBusinessShortCode(shortCode);
        stkRequest.setPassword(password);
        stkRequest.setTimestamp(timestamp);
        stkRequest.setTransactionType("CustomerPayBillOnline");
        stkRequest.setAmount(amount);
        stkRequest.setPartyA(formattedPhone);
        stkRequest.setPartyB(shortCode);
        stkRequest.setPhoneNumber(formattedPhone);
        stkRequest.setCallBackURL(callbackUrl);
        stkRequest.setAccountReference("Heritage_Pay");
        stkRequest.setTransactionDesc("Payment");

        String json = mapper.writeValueAsString(stkRequest);
        RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));

        Request request = new Request.Builder()
                .url("https://sandbox.safaricom.co.ke/mpesa/stkpush/v1/processrequest")
                .post(body)
                .addHeader("Authorization", "Bearer " + token)
                .build();

        log.info("Sending STK Push to Safaricom...");
        try (Response response = client.newCall(request).execute()) {
            log.info("Got response from Safaricom");
            String responseBodyString = response.body() != null ? response.body().string() : "{}";
            log.info("Safaricom Raw Response: {}", responseBodyString);

            // Guard against non-JSON responses (e.g. Safaricom timeout errors)
            if (!responseBodyString.trim().startsWith("{")) {
                throw new RuntimeException("Safaricom returned non-JSON response: " + responseBodyString);
            }

            return mapper.readValue(responseBodyString, MpesaResponse.class);
        }
    }

    @Transactional
    @Override
    public void processCallback(JsonNode payload, Long userId, Long quoteId) {
        log.info("M-Pesa Callback payload: {}", payload);
        JsonNode stkCallback = payload.get("Body").get("stkCallback");
        int resultCode = stkCallback.get("ResultCode").asInt();
        String checkoutRequestId = stkCallback.get("CheckoutRequestID").asText();

        if (mpesaRepository.existsByCheckoutRequestId(checkoutRequestId)) return;

        String resultDesc = stkCallback.get("ResultDesc").asText();

        MpesaTransaction mpesaTransaction = new MpesaTransaction();
        mpesaTransaction.setCheckoutRequestId(checkoutRequestId);
        mpesaTransaction.setResultCode(resultCode);
        mpesaTransaction.setResultDesc(resultDesc);

        if (resultCode == 0) {
            JsonNode metadata = stkCallback.get("CallbackMetadata").get("Item");
            for (JsonNode item : metadata) {
                String name = item.get("Name").asText();
                JsonNode valueNode = item.get("Value");
                if (valueNode != null) {
                    switch (name) {
                        case "MpesaReceiptNumber" -> mpesaTransaction.setTransactionId(valueNode.asText());
                        case "Amount" -> mpesaTransaction.setAmount(valueNode.asDouble());
                        case "PhoneNumber" -> mpesaTransaction.setPhoneNumber(valueNode.asText());
                    }
                }
            }
            mpesaRepository.save(mpesaTransaction);
            digitaxService.reportTransaction(mpesaTransaction);

            paymentRepository.findByQuoteIdAndStatus(quoteId, "PENDING")
                    .ifPresentOrElse(payment -> {
                        payment.setStatus("SUCCESS");
                        payment.setTransactionId(mpesaTransaction.getTransactionId());
                        payment.setAmount(mpesaTransaction.getAmount());
                        payment.setTransactionId(mpesaTransaction.getTransactionId());
                        payment.setCustomerPhoneNumber(mpesaTransaction.getPhoneNumber());
                        paymentRepository.save(payment);
                        log.info("Payment updated to SUCCESS for quoteId={}", quoteId);
                    }, () -> {
                        Payment payment = new Payment();
                        payment.setUserId(userId);
                        payment.setQuoteId(quoteId);
                        payment.setAmount(mpesaTransaction.getAmount());
                        payment.setTransactionId(mpesaTransaction.getTransactionId());
                        payment.setPaymentMethod("STK_PUSH");
                        payment.setStatus("SUCCESS");
                        paymentRepository.save(payment);
                        log.info("New SUCCESS payment created for quoteId={}", quoteId);
                    });
        } else {
            mpesaRepository.save(mpesaTransaction);

            paymentRepository.findByQuoteIdAndStatus(quoteId, "PENDING")
                    .ifPresent(payment -> {
                        payment.setStatus("FAILED");
                        payment.setTransactionId(mpesaTransaction.getTransactionId());
                        paymentRepository.save(payment);
                        log.info("Payment updated to FAILED for quoteId={}", quoteId);
                    });
        }
    }

    @Override
    public String registerC2BUrls() throws IOException {
        String token = getAccessToken();
        Map<String, String> body = Map.of(
                "ShortCode", c2bShortCode,
                "ResponseType", "Completed",
                "ConfirmationURL", callbackBaseUrl + "/api/v1/payments/c2b/confirm",
                "ValidationURL", callbackBaseUrl + "/api/v1/payments/c2b/validate"
        );

        String json = mapper.writeValueAsString(body);
        RequestBody requestBody = RequestBody.create(json, MediaType.parse("application/json"));

        Request request = new Request.Builder()
                .url("https://sandbox.safaricom.co.ke/mpesa/c2b/v2/registerurl")
                .post(requestBody)
                .addHeader("Authorization", "Bearer " + token)
                .build();

        try (Response response = client.newCall(request).execute()) {
            return response.body() != null ? response.body().string() : "{}";
        }
    }

    @Transactional
    @Override
    public void processC2BPayment(JsonNode payload, Long userId, Long quoteId) {
        String transId = payload.path("TransID").asText();

        if (mpesaRepository.existsByTransactionId(transId)) {
            log.warn("C2B Transaction {} already exists. Skipping processing.", transId);
            return;
        }

        log.info("C2B Full Payload: {}", payload.toPrettyString());

        MpesaTransaction tx = new MpesaTransaction();
        tx.setTransactionId(transId);
        tx.setAmount(payload.path("TransAmount").asDouble());
        tx.setPhoneNumber(payload.path("MSISDN").asText());
        tx.setResultCode(0);
        tx.setResultDesc("C2B Payment Processed");

        MpesaTransaction savedTx = mpesaRepository.saveAndFlush(tx);
        log.info("MpesaTransaction record created: {}", transId);

        digitaxService.reportTransaction(savedTx);

        Payment payment = new Payment();
        payment.setUserId(userId);
        payment.setQuoteId(quoteId);
        payment.setAmount(savedTx.getAmount());
        payment.setTransactionId(savedTx.getTransactionId());
        payment.setTransactionId(transId);
        payment.setPaymentMethod("C2B");
        payment.setCustomerPhoneNumber(savedTx.getPhoneNumber());
        payment.setStatus("SUCCESS");

        try {
            paymentRepository.save(payment);
            log.info("C2B Payment successfully linked and saved for quoteId={}, transId={}", quoteId, transId);
        } catch (Exception e) {
            log.error("Failed to save Payment record for M-Pesa TX {}: {}", transId, e.getMessage());
            throw e; // Re-throw to trigger Transactional rollback
        }
    }

    @Override
    public void createPendingPayment(Long userId, Long quoteId, String amount, MpesaResponse response) {
        Payment payment = new Payment();
        payment.setUserId(userId);
        payment.setQuoteId(quoteId);
        payment.setAmount(Double.parseDouble(amount));
        payment.setStatus("PENDING");
        payment.setPaymentMethod("STK_PUSH");
        payment.setCheckoutRequestId(response.getCheckoutRequestID());
        paymentRepository.save(payment);
    }
}