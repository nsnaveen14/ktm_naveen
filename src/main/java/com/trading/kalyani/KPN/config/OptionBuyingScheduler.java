package com.trading.kalyani.KPN.config;

import com.trading.kalyani.KPN.service.OptionBuyingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler for the Option Buying Strategy.
 * Runs every 60 seconds to check signals and monitor open positions.
 */
@Component
public class OptionBuyingScheduler {

    private static final Logger logger = LoggerFactory.getLogger(OptionBuyingScheduler.class);

    @Autowired
    private OptionBuyingService optionBuyingService;

    /**
     * Every minute: check for new signals and monitor exits.
     * fixedDelay ensures the previous run finishes before the next one starts.
     */
    @Scheduled(fixedDelay = 60000)
    public void run() {
        try {
            optionBuyingService.checkAndExecute();
            optionBuyingService.monitorOpenTrades();
        } catch (Exception e) {
            logger.error("OptionBuyingScheduler error: {}", e.getMessage(), e);
        }
    }
}
