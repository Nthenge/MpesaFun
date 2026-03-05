package com.heritage.payment_service.service;

import com.heritage.payment_service.entity.MpesaTransaction;
import com.heritage.payment_service.entity.Payment;

public interface DigitaxService {
    void reportTransaction(MpesaTransaction mpesaTransaction);

    void reportTransaction(Payment payment, String transactionId, String phoneNumber);
}
