package com.heritage.payment_service.config;

import com.heritage.payment_service.service.MpesaService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@Profile({"dev", "local"})
public class MpesaStartupConfig {

    private final MpesaService mpesaService;

    @PostConstruct
    public void autoRegisterC2B() {
        try {
            String response = mpesaService.registerC2BUrls();
            log.info("C2B URLs registered successfully: {}", response);
        } catch (Exception e) {
            log.error("Failed to auto-register C2B URLs", e);
        }
    }
}
