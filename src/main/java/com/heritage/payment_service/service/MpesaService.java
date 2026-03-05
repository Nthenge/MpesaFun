package com.heritage.payment_service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.heritage.payment_service.dto.response.MpesaResponse;
import com.heritage.payment_service.entity.Payment;

import java.io.IOException;

public interface MpesaService {
    String getAccessToken();

    MpesaResponse initiateStkPush(String phone, String amount) throws IOException;

    void processCallback(JsonNode payload, Long userId, Long quoteId);

    String registerC2BUrls() throws IOException;

    void processC2BPayment(JsonNode payload, Long userId, Long quoteId);


    void createPendingPayment(Long userId, Long quoteId, String amount, MpesaResponse response);
}
