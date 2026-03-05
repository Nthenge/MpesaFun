package com.heritage.payment_service.repository;

import com.heritage.payment_service.entity.MpesaTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface MpesaTransactionRepository extends JpaRepository<MpesaTransaction, Long> {
    boolean existsByCheckoutRequestId(String checkoutRequestId);
    boolean existsByTransactionId(String transactionId);

    List<MpesaTransaction> findByReportedToDigitaxFalseAndResultCode(Integer resultCode);

    Optional<MpesaTransaction> findByTransactionId(String transactionId);
}


