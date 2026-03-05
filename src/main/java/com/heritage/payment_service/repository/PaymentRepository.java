package com.heritage.payment_service.repository;

import com.heritage.payment_service.entity.MpesaTransaction;
import com.heritage.payment_service.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByQuoteId(Long quoteId); // Long not String

    Optional<Payment> findByCheckoutRequestId(String checkoutRequestId);

    Optional<Payment> findByQuoteIdAndStatus(Long quoteId, String status);

    Optional<Payment> findByTransactionId(String transId);

    List<Payment> findByStatusAndKraReceiptNumberIsNull(String success);
}
