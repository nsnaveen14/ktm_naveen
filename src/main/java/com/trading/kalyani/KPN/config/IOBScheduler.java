package com.trading.kalyani.KPN.config;

import com.trading.kalyani.KPN.entity.InternalOrderBlock;
import com.trading.kalyani.KPN.service.InternalOrderBlockService;
import com.trading.kalyani.KPN.service.TelegramNotificationService;
import com.trading.kalyani.KPN.service.AutoTradingService;
import com.trading.kalyani.KPN.service.RealTimePriceService;
import com.trading.kalyani.KPN.service.PerformanceTrackingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Collections;

import static com.trading.kalyani.KPN.constants.ApplicationConstants.*;

/**
 * Scheduler for Internal Order Block (IOB) scanning and mitigation checking.
 * <p>
 * This scheduler runs every minute during market hours to:
 * 1. Scan for new IOBs on NIFTY
 * 2. Check if any existing IOBs have been mitigated
 * 3. Send Telegram alerts for high-confidence IOB signals (>51%)
 * <p>
 * The polling technique ensures reliable IOB detection without depending on WebSocket connections.
 */
@Component
public class IOBScheduler {

    private static final Logger logger = LoggerFactory.getLogger(IOBScheduler.class);

    // Minimum confidence threshold for Telegram alerts (51%)
    private static final double TELEGRAM_ALERT_CONFIDENCE_THRESHOLD = 51.0;

    // Alerted IOB entries older than this are evicted to keep the map bounded
    private static final int ALERT_CACHE_TTL_HOURS = 4;
    private static final String IST_ZONE = "Asia/Kolkata";
    private static final ZoneId IST_ZONE_ID = ZoneId.of(IST_ZONE);

    @Autowired
    private InternalOrderBlockService iobService;

    @Autowired(required = false)
    private TelegramNotificationService telegramNotificationService;

    @Autowired(required = false)
    private AutoTradingService autoTradingService;

    @Autowired(required = false)
    private RealTimePriceService realTimePriceService;

    @Autowired(required = false)
    private PerformanceTrackingService performanceTrackingService;

    @Value("${iob.scanner.enabled:true}")
    private boolean iobScannerEnabled;

    // Track the last price for each instrument to detect mitigation
    private final Map<Long, Double> lastPrices = new ConcurrentHashMap<>();

    // Track already alerted IOB IDs with timestamp — evicted after ALERT_CACHE_TTL_HOURS to stay bounded
    private final Map<Long, LocalDateTime> alertedIOBs = new ConcurrentHashMap<>();

    /**
     * IOB Scanner Job - Runs every minute during first trading hour (9:16 AM to 9:59 AM)
     */
    @Scheduled(cron = "0 16-59 9 * * MON-FRI", zone = IST_ZONE)
    public void runIOBScannerFirstHour() {
        executeIOBScanner("FirstHour");
    }

    /**
     * IOB Scanner Job - Runs every minute during mid-day (10:00 AM to 2:59 PM)
     */
    @Scheduled(cron = "0 * 10-14 * * MON-FRI", zone = IST_ZONE)
    public void runIOBScannerMidDay() {
        executeIOBScanner("MidDay");
    }

    /**
     * IOB Scanner Job - Runs every minute during last trading hour (3:00 PM to 3:29 PM)
     */
    @Scheduled(cron = "0 0-29 15 * * MON-FRI", zone = IST_ZONE)
    public void runIOBScannerLastHour() {
        executeIOBScanner("LastHour");
    }

    /**
     * Common method to execute IOB scanning with proper checks
     */
    private void executeIOBScanner(String period) {
        if (!iobScannerEnabled) {
            logger.debug("IOB Scanner is disabled via configuration");
            return;
        }

        try {
            logger.debug("[{}] Running IOB scanner at {}", period, LocalTime.now(IST_ZONE_ID));

            // Fetch prices once — used for mitigation, auto-trading, and trade outcome checks
            Map<Long, Double> currentPrices = realTimePriceService != null
                    ? realTimePriceService.getAllCurrentPrices()
                    : new HashMap<>(lastPrices);

            // Evict stale alert cache entries once per cycle before scanning any instrument
            LocalDateTime cutoff = LocalDateTime.now(IST_ZONE_ID).minusHours(ALERT_CACHE_TTL_HOURS);
            alertedIOBs.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));

            // Scan NIFTY only
            scanAndProcessIOBs(NIFTY_INSTRUMENT_TOKEN, "NIFTY", currentPrices);

            // Process auto trading if enabled
            processAutoTrading(currentPrices);

            // Check and update trade outcomes
            checkTradeOutcomes(currentPrices);

        } catch (Exception e) {
            logger.error("Error in IOB scanner [{}]: {}", period, e.getMessage(), e);
        }
    }

    /**
     * Process auto trading based on current prices
     */
    private void processAutoTrading(Map<Long, Double> currentPrices) {
        if (autoTradingService == null) {
            return;
        }

        try {
            if (!currentPrices.isEmpty()) {
                autoTradingService.processAutoTrades(currentPrices);
            }
        } catch (Exception e) {
            logger.error("Error processing auto trades: {}", e.getMessage(), e);
        }
    }

    /**
     * Check and update outcomes for open trades
     */
    private void checkTradeOutcomes(Map<Long, Double> currentPrices) {
        if (performanceTrackingService == null) {
            return;
        }

        try {
            if (!currentPrices.isEmpty()) {
                performanceTrackingService.checkTradeOutcomes(currentPrices);
            }
        } catch (Exception e) {
            logger.error("Error checking trade outcomes: {}", e.getMessage(), e);
        }
    }

    /**
     * Scan for IOBs and process them (alerts + mitigation check)
     */
    private void scanAndProcessIOBs(Long instrumentToken, String instrumentName, Map<Long, Double> currentPrices) {
        try {
            // Scan for new IOBs
            List<InternalOrderBlock> detectedIOBs = iobService.scanForIOBs(instrumentToken);

            if (detectedIOBs != null && !detectedIOBs.isEmpty()) {
                logger.info("Detected {} IOBs for {}", detectedIOBs.size(), instrumentName);

                LocalDateTime now = LocalDateTime.now(IST_ZONE_ID);
                for (InternalOrderBlock iob : detectedIOBs) {
                    if (shouldSendAlert(iob)) {
                        sendIOBAlert(iob);
                        if (iob.getId() != null) {
                            alertedIOBs.put(iob.getId(), now);
                        }
                    }
                }
            }

            // Check for mitigation of existing IOBs using live price
            Double livePrice = currentPrices.get(instrumentToken);
            checkMitigationForInstrument(instrumentToken, instrumentName, livePrice);

        } catch (Exception e) {
            logger.error("Error scanning IOBs for {}: {}", instrumentName, e.getMessage(), e);
        }
    }

    /**
     * Check if any existing IOBs have been mitigated
     */
    private void checkMitigationForInstrument(Long instrumentToken, String instrumentName, Double livePrice) {
        try {
            if (livePrice == null) {
                logger.warn("No live price available for {} — skipping mitigation check", instrumentName);
                return;
            }

            // Check for mitigation using live price from realTimePriceService
            // Alert is sent inside checkMitigation() — no duplicate alert here
            List<InternalOrderBlock> mitigatedIOBs = iobService.checkMitigation(instrumentToken, livePrice);

            if (mitigatedIOBs != null && !mitigatedIOBs.isEmpty()) {
                logger.info("Detected {} mitigated IOBs for {} at price {}",
                        mitigatedIOBs.size(), instrumentName, livePrice);
            }

            // Check if any targets have been hit for open trades
            iobService.checkTargetHits(instrumentToken, livePrice);

            // Update last price
            lastPrices.put(instrumentToken, livePrice);

        } catch (Exception e) {
            logger.error("Error checking mitigation for {}: {}", instrumentName, e.getMessage(), e);
        }
    }

    /**
     * Check if we should send an alert for this IOB
     */
    private boolean shouldSendAlert(InternalOrderBlock iob) {
        if (iob == null || iob.getId() == null) {
            return false;
        }

        // Check if already alerted within TTL window
        if (alertedIOBs.containsKey(iob.getId())) {
            return false;
        }

        Double confidence = iob.getSignalConfidence();
        return confidence != null && confidence > TELEGRAM_ALERT_CONFIDENCE_THRESHOLD;
    }

    /**
     * Send Telegram alert for new IOB signal
     */
    private void sendIOBAlert(InternalOrderBlock iob) {
        if (telegramNotificationService == null) {
            logger.debug("TelegramNotificationService is not available — skipping IOB alert");
            return;
        }

        try {
            String alertType = iob.getObType() != null && iob.getObType().contains("BULLISH") ? "🟢 BULLISH" : "🔴 BEARISH";

            StringBuilder message = new StringBuilder();
            message.append(String.format("*%s IOB Detected*\n", alertType));
            message.append("⏳ *Waiting for price to enter zone before entry*\n");
            message.append(String.format("📈 Instrument: *%s*\n", iob.getInstrumentName()));
            message.append(String.format("⏰ Timeframe: %s\n", iob.getTimeframe()));
            message.append(String.format("📅 OB Candle Time: %s\n", iob.getObCandleTime()));
            message.append(String.format("🕐 Detection Time: %s\n", iob.getDetectionTimestamp() != null ? iob.getDetectionTimestamp().toString() : "N/A"));
            message.append("\n");
            message.append(String.format("🎯 Zone High: %.2f\n", iob.getZoneHigh() != null ? iob.getZoneHigh() : 0.0));
            message.append(String.format("🎯 Zone Low: %.2f\n", iob.getZoneLow() != null ? iob.getZoneLow() : 0.0));
            message.append(String.format("💰 Current Price: %.2f\n", iob.getCurrentPrice() != null ? iob.getCurrentPrice() : 0.0));
            // Show how far price is from zone
            if (iob.getCurrentPrice() != null && iob.getZoneHigh() != null && iob.getZoneLow() != null) {
                double price = iob.getCurrentPrice();
                boolean aboveZone = price > iob.getZoneHigh();
                boolean belowZone = price < iob.getZoneLow();
                if (aboveZone) {
                    message.append(String.format("📏 Distance to Zone: %.2f pts above\n", price - iob.getZoneHigh()));
                } else if (belowZone) {
                    message.append(String.format("📏 Distance to Zone: %.2f pts below\n", iob.getZoneLow() - price));
                } else {
                    message.append("📍 *Price is INSIDE zone — entry pending*\n");
                }
            }
            message.append("\n");
            message.append(String.format("📍 Trade Direction: *%s*\n", iob.getTradeDirection()));
            message.append(String.format("🚀 Entry Price: %.2f\n", iob.getEntryPrice() != null ? iob.getEntryPrice() : 0.0));
            message.append(String.format("🛡️ Stop Loss: %.2f\n", iob.getStopLoss() != null ? iob.getStopLoss() : 0.0));
            message.append(String.format("🎯 Target 1: %.2f\n", iob.getTarget1() != null ? iob.getTarget1() : 0.0));
            message.append(String.format("🎯 Target 2: %.2f\n", iob.getTarget2() != null ? iob.getTarget2() : 0.0));
            message.append(String.format("🎯 Target 3: %.2f\n", iob.getTarget3() != null ? iob.getTarget3() : 0.0));
            message.append("\n");
            message.append(String.format("📊 Risk:Reward: %.2f\n", iob.getRiskRewardRatio() != null ? iob.getRiskRewardRatio() : 0.0));
            message.append(String.format("💪 Confidence: *%.1f%%*\n", iob.getSignalConfidence()));
            if (Boolean.TRUE.equals(iob.getHasFvg())) {
                boolean fvgValid = Boolean.TRUE.equals(iob.getFvgValid());
                String fvgStatus = fvgValid ? "Valid ✅" : "Invalid ❌";
                if (iob.getFvgValidationScore() != null) {
                    message.append(String.format("• FVG: %s (Score: %.0f%%)\n", fvgStatus, iob.getFvgValidationScore()));
                } else {
                    message.append(String.format("• FVG: %s\n", fvgStatus));
                }
            } else {
                message.append("• FVG: None ➖\n");
            }

            // Create trade data map for the alert
            Map<String, Object> tradeData = new HashMap<>();
            tradeData.put("iobId", iob.getId());
            tradeData.put("obType", iob.getObType());
            tradeData.put("zoneHigh", iob.getZoneHigh());
            tradeData.put("zoneLow", iob.getZoneLow());
            tradeData.put("confidence", iob.getSignalConfidence());

            telegramNotificationService.sendTradeAlert("📊 IOB SIGNAL ALERT", message.toString(), tradeData);
            logger.info("Sent Telegram alert for IOB: {} {}", iob.getObType(), iob.getInstrumentName());

        } catch (Exception e) {
            logger.error("Failed to send IOB Telegram alert: {}", e.getMessage(), e);
        }
    }

    /**
     * Clear alerted IOBs cache - Called at end of day or manually
     */
    public void clearAlertedIOBsCache() {
        int size = alertedIOBs.size();
        alertedIOBs.clear();
        logger.info("Cleared alerted IOBs cache ({} entries)", size);
    }

    /**
     * End of Day cleanup - Runs at 3:35 PM
     */
    @Scheduled(cron = "0 35 15 * * MON-FRI", zone = IST_ZONE)
    public void runEndOfDayCleanup() {
        logger.info("Running IOB end of day cleanup");

        // In-memory cleanup — these are infallible, run unconditionally
        clearAlertedIOBsCache();
        lastPrices.clear();
        // Clear stale prices from RealTimePriceService so tomorrow's zone checks
        // don't use today's last tick as a starting price
        if (realTimePriceService != null) {
            try {
                realTimePriceService.disconnect();
            } catch (Exception e) {
                logger.warn("Error disconnecting real-time price feed at EOD: {}", e.getMessage());
            }
        }

        // DB cleanup — isolated so a timeout doesn't mask the in-memory steps above
        try {
            iobService.expireOldIOBs();
        } catch (Exception e) {
            logger.error("Error expiring old IOBs at EOD: {}", e.getMessage(), e);
        }

        logger.info("IOB end of day cleanup completed");
    }
}
