package com.heritage.payment_service.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "mpesa_transactions", uniqueConstraints = {
        @UniqueConstraint(columnNames = "checkoutRequestId")
})
public class MpesaTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String transactionId;
    private String phoneNumber;
    private Double amount;
    private String checkoutRequestId;
    private Integer resultCode;
    private String resultDesc;
    private LocalDateTime createdAt;
//    private String kraReceiptNumber;

    private boolean reportedToDigitax = false;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}