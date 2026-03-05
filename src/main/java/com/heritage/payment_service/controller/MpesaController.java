package com.heritage.payment_service.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.heritage.payment_service.dto.response.MpesaResponse;
import com.heritage.payment_service.entity.Payment;
import com.heritage.payment_service.repository.PaymentRepository;
import com.heritage.payment_service.service.MpesaService;
import com.heritage.payment_service.utils.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Tag(name = "M-Pesa Payments", description = "Endpoints for STK Push and C2B M-Pesa integrations")
public class MpesaController {

    private final MpesaService mpesaService;
    private final ObjectMapper mapper;
    private final PaymentRepository paymentRepository;
    private final JwtUtil jwtUtil;

    @Operation(
            summary = "Initiate STK Push",
            description = "Triggers an M-Pesa STK Push request to a customer's phone number for a specific quote."
    )
    @ApiResponse(responseCode = "200", description = "STK Push request sent successfully",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = MpesaResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid phone number, quoteId, or amount supplied")
    @ApiResponse(responseCode = "500", description = "Internal server error")
//    @PostMapping("/checkout")
//    public MpesaResponse checkout(
//            @RequestParam String phone,
//            @RequestParam String amount,
//            @RequestParam Long quoteId,
//            @RequestHeader("Authorization") String authHeader
//    ) throws IOException {
//        Long userId = jwtUtil.extractUserId(authHeader);
//        MpesaResponse response = mpesaService.initiateStkPush(phone, amount);
//        mpesaService.createPendingPayment(userId, quoteId, amount, response);
//        return response;
//    }


    @PostMapping("/checkout")
    public MpesaResponse checkout(
            @RequestParam String phone,
            @RequestParam String amount,
            @RequestParam Long quoteId
    ) throws IOException {
        MpesaResponse response = mpesaService.initiateStkPush(phone, amount);
        // temporarily hardcode a userId for testing
        mpesaService.createPendingPayment(1L, quoteId, amount, response);
        return response;
    }



    @PostMapping("/callback")
    @Operation(
            summary = "Handle STK Push Callback",
            description = "Receives asynchronous callback from Safaricom after STK Push processing."
    )
    @ApiResponse(responseCode = "200", description = "Callback processed successfully")
    @ApiResponse(responseCode = "500", description = "Error processing callback")
    public String handleCallback(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Raw callback payload from Safaricom",
                    required = true
            )
            @RequestBody JsonNode payload
    ) {
        try {
            String checkoutRequestId = payload.path("Body")
                    .path("stkCallback")
                    .path("CheckoutRequestID")
                    .asText();

            var tempPayment = paymentRepository.findByCheckoutRequestId(checkoutRequestId);

            Long userId = tempPayment.map(Payment::getUserId).orElse(null);
            Long quoteId = tempPayment.map(Payment::getQuoteId).orElse(null);

            mpesaService.processCallback(payload, userId, quoteId);

            return "Success";
        } catch (Exception e) {
            log.error("Error processing STK Push callback: {}", e.getMessage(), e);
            return "Error";
        }
    }

    @PostMapping("/c2b/register")
    @Operation(
            summary = "Register C2B URLs",
            description = "Registers confirmation and validation URLs with Safaricom for C2B payments."
    )
    @ApiResponse(responseCode = "200", description = "C2B URLs registered successfully")
    @ApiResponse(responseCode = "500", description = "Failed to register C2B URLs")
    public String registerC2BUrls() throws IOException {
        return mpesaService.registerC2BUrls();
    }

    @PostMapping("/c2b/confirm")
    @Operation(
            summary = "Handle C2B Confirmation",
            description = "Receives C2B payment confirmation from Safaricom."
    )
    @ApiResponse(responseCode = "200", description = "C2B payment processed successfully")
    public String confirmTransaction(@RequestBody JsonNode payload) {
        String transId = payload.path("TransID").asText();

        // Lookup Payment info for this transId if exists
        Optional<Payment> temp = paymentRepository.findByTransactionId(transId);
        Long userId = temp.map(Payment::getUserId).orElse(null);
        Long quoteId = temp.map(Payment::getQuoteId).orElse(null);

        mpesaService.processC2BPayment(payload, userId, quoteId);
        return "Success";
    }

    @PostMapping("/c2b/validate")
    @Operation(
            summary = "Validate C2B Transaction",
            description = "Validates a C2B payment before Safaricom completes the transaction."
    )
    @ApiResponse(responseCode = "200", description = "Validation response returned to Safaricom")
    public ObjectNode validateTransaction(@RequestBody JsonNode payload) {
        log.info("C2B Validation Payload Received: {}", payload.path("TransID").asText());

        ObjectNode response = mapper.createObjectNode();
        response.put("ResultCode", 0);
        response.put("ResultDesc", "Accepted");

        return response;
    }
}