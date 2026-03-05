package com.heritage.payment_service.scheduler;

import com.heritage.payment_service.entity.Payment;
import com.heritage.payment_service.repository.PaymentRepository;
import com.heritage.payment_service.service.DigitaxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DigitaxRetryScheduler {

    private final PaymentRepository paymentRepository;
    private final DigitaxService digitaxService;

    @Scheduled(fixedRate = 1800000) // runs every 30 minutes
    @Transactional(readOnly = true)
    public void retryFailedReports() {
        log.info("e-TIMS: Checking for unreported payments across all channels...");

        List<Payment> pendingPayments = paymentRepository.findByStatusAndKraReceiptNumberIsNull("SUCCESS");

        for (Payment payment : pendingPayments) {
            try {
                String transactionId = getTransactionId(payment);
                String phoneNumber = getPhoneNumber(payment);

                if (transactionId != null) {
                    log.info("Retrying Digitax for {} Payment, Ref: {}", payment.getPaymentMethod(), transactionId);
                    digitaxService.reportTransaction(payment, transactionId, phoneNumber);
                }
            } catch (Exception e) {
                log.error("Failed to retry Digitax reporting for Payment ID {}: {}", payment.getId(), e.getMessage());
            }
        }
    }

    private String getTransactionId(Payment payment) {
        return payment.getTransactionId();
    }

    private String getPhoneNumber(Payment payment) {
        if (payment.getCustomerPhoneNumber() != null && !payment.getCustomerPhoneNumber().isEmpty()) {
            return payment.getCustomerPhoneNumber();
        }
        return "0000000000";
    }
}