package com.heritage.payment_service.entity;

import jakarta.persistence.*;
import lombok.*;

@Table(name = "payments")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private Long quoteId;
    private Double amount;
    private String paymentMethod;

    private String status; // "PENDING", "SUCCESS", "FAILED"
    @Column(unique = true)
    private String transactionId;
    private String kraReceiptNumber;
    private String customerPhoneNumber;
    private String customerName;

//    @ManyToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
//    @JoinColumn(name = "mpesa_transaction_id")
//    private MpesaTransaction mpesaTransaction;

    private String checkoutRequestId;
}