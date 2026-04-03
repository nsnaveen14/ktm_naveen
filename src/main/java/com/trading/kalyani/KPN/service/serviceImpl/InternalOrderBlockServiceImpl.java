package com.trading.kalyani.KPN.service.serviceImpl;

import com.trading.kalyani.KPN.entity.InternalOrderBlock;
import com.trading.kalyani.KPN.model.HistoricalDataRequest;
import com.trading.kalyani.KPN.model.HistoricalDataResponse;
import com.trading.kalyani.KPN.model.HistoricalDataResponse.HistoricalCandle;
import com.trading.kalyani.KPN.repository.InternalOrderBlockRepository;
import com.trading.kalyani.KPN.service.InstrumentService;
import com.trading.kalyani.KPN.service.InternalOrderBlockService;
import com.trading.kalyani.KPN.service.SimulatedTradingService;
import com.trading.kalyani.KPN.service.TelegramNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.trading.kalyani.KPN.constants.ApplicationConstants.*;

/**
 * Implementation of InternalOrderBlockService for detecting and trading
 * Internal Order Blocks (IOBs) in 5-minute timeframe.
 *
 * IOB Detection Logic:
 * 1. Bullish IOB: Last bearish candle before a Break of Structure (BOS) to the upside
 *    - Look for swing low being broken (bearish BOS)
 *    - Then price reverses and breaks above previous swing high (bullish BOS)
 *    - The last bearish candle before the bullish move is the IOB
 *
 * 2. Bearish IOB: Last bullish candle before a Break of Structure (BOS) to the downside
 *    - Look for swing high being broken (bullish BOS)
 *    - Then price reverses and breaks below previous swing low (bearish BOS)
 *    - The last bullish candle before the bearish move is the IOB
 */
@Service
public class InternalOrderBlockServiceImpl implements InternalOrderBlockService {

    private static final Logger logger = LoggerFactory.getLogger(InternalOrderBlockServiceImpl.class);

    private static final DateTimeFormatter SIGNATURE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
    private static final DateTimeFormatter TIME_FORMATTER     = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private InternalOrderBlockRepository iobRepository;

    @Autowired
    private InstrumentService instrumentService;

    @Lazy
    @Autowired(required = false)
    private SimulatedTradingService simulatedTradingService;

    @Lazy
    @Autowired(required = false)
    private com.trading.kalyani.KPN.service.CandlePredictionService candlePredictionService;

    @Autowired(required = false)
    private TelegramNotificationService telegramNotificationService;

    @Autowired(required = false)
    private com.trading.kalyani.KPN.service.MarketStructureService marketStructureService;

    @Autowired(required = false)
    private com.trading.kalyani.KPN.service.VolumeProfileService volumeProfileService;

    @Autowired(required = false)
    private com.trading.kalyani.KPN.service.RiskManagementService riskManagementService;

    // Configuration
    private static final String DEFAULT_TIMEFRAME = "5min";
    private static final int LOOKBACK_CANDLES = 100; // Candles to analyze
    private static final int SWING_LOOKBACK = 5; // Candles each side to identify swing points (5 × 5min = 25 min per side)
    private static final double MIN_DISPLACEMENT_BODY_PERCENT = 0.6; // Minimum body-to-range ratio for displacement
    private static final double IOB_ZONE_BUFFER_PERCENT = 0.1; // Buffer for zone entry (percentage, e.g. 0.1 = 0.1%)
    private static final double MIN_FVG_SIZE_PERCENT = 0.02; // Minimum FVG size as % of price (~5 pts on NIFTY at 25000)

    // Real-time BOS (EARLY signal) guards
    /** RVOL must be ≥ this ratio to pass the wick-trap filter. 1.2 = 20% above average pace. */
    private static final double REALTIME_MIN_RVOL = 1.2;
    /** EARLY IOBs older than this (minutes) that weren't upgraded are expired (BOS candle closed without confirming). */
    private static final int EARLY_IOB_EXPIRY_MINUTES = 6;
    /** If confirmed price is already > this % from the OB zone, RR is gone — skip the upgrade. */
    private static final double MAX_CONFIRMED_DISTANCE_PERCENT = 0.5;

    @Override
    public List<InternalOrderBlock> scanForIOBs(Long instrumentToken) {
        return scanForIOBs(instrumentToken, DEFAULT_TIMEFRAME);
    }

    @Override
    public List<InternalOrderBlock> scanForIOBs(Long instrumentToken, String timeframe) {
        logger.info("Scanning for IOBs - Token: {}, Timeframe: {}", instrumentToken, timeframe);

        try {
            List<HistoricalCandle> candles = fetchHistoricalCandles(instrumentToken, timeframe);
            if (candles == null || candles.size() < LOOKBACK_CANDLES / 2) {
                logger.warn("Insufficient candles for IOB analysis. Got: {}", candles != null ? candles.size() : 0);
                return Collections.emptyList();
            }

            double currentPrice = candles.get(candles.size() - 1).getClose();
            List<SwingPoint> swingHighs = identifySwingHighs(candles);
            List<SwingPoint> swingLows = identifySwingLows(candles);
            logger.debug("Found {} swing highs and {} swing lows", swingHighs.size(), swingLows.size());

            List<InternalOrderBlock> candidates = new ArrayList<>();
            candidates.addAll(detectIOBs(true,  instrumentToken, timeframe, candles, swingHighs, swingLows, currentPrice));
            candidates.addAll(detectIOBs(false, instrumentToken, timeframe, candles, swingHighs, swingLows, currentPrice));

            // Real-time BOS: if closed-candle scan found nothing, check whether live NIFTY price
            // has already crossed a swing level for a valid OB+displacement pattern.
            // Reduces detection lag from 15 min (3 closed candles) to ~10 min average.
            if (candidates.isEmpty() && candlePredictionService != null) {
                try {
                    Map<String, Object> liveData = candlePredictionService.getLiveTickData();
                    if (liveData != null) {
                        Object ltpObj = liveData.get("niftyLTP");
                        Double liveLTP = ltpObj instanceof Number ? ((Number) ltpObj).doubleValue() : null;
                        if (liveLTP != null && liveLTP > 0) {
                            candidates.addAll(detectRealtimeBOS(instrumentToken, timeframe, candles, swingHighs, swingLows, liveLTP));
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Real-time BOS check skipped: {}", e.getMessage());
                }
            }

            // Stamp signatures before dedup check so isDuplicateIOB is a pure predicate.
            candidates.forEach(iob -> iob.setIobSignature(generateIOBSignature(iob)));

            // Route each candidate:
            //  EARLY  → persist as partial signal (real-time BOS, candle still forming)
            //  FRESH  → check if upgrading an existing EARLY IOB; otherwise normal dedup
            List<InternalOrderBlock> newIOBs = new ArrayList<>();
            for (InternalOrderBlock candidate : candidates) {
                if ("EARLY".equals(candidate.getStatus())) {
                    // Only save if no record exists yet (guard against re-scan of same candle)
                    if (!iobRepository.existsByIobSignature(candidate.getIobSignature())) {
                        newIOBs.add(candidate);
                    } else {
                        logger.debug("Real-time BOS: EARLY IOB already persisted for signature {}", candidate.getIobSignature());
                    }
                } else {
                    // Closed-candle detection: upgrade EARLY→FRESH if a partial already exists
                    Optional<InternalOrderBlock> earlyExisting = iobRepository.findByIobSignature(candidate.getIobSignature())
                            .filter(e -> "EARLY".equals(e.getStatus()));
                    if (earlyExisting.isPresent()) {
                        upgradeEarlyToFresh(earlyExisting.get(), candidate, candles);
                    } else if (!isDuplicateIOB(candidate)) {
                        newIOBs.add(candidate);
                    }
                }
            }

            // Post-pass: assign FVG priority rankings (Factor 4) over the unique set only
            assignFvgPriority(newIOBs);

            for (InternalOrderBlock iob : newIOBs) {
                if ("EARLY".equals(iob.getStatus())) {
                    persistEarlyIOB(iob, candles);
                } else {
                    persistNewIOB(iob, candles);
                }
            }

            logger.info("Saved {} new IOBs for token: {}", newIOBs.size(), instrumentToken);

            // Item 2: Expire stale EARLY IOBs whose BOS candle has since closed without confirming.
            // These represent wick-trap signals — clean them up so future patterns aren't blocked.
            expireStaleEarlyIOBs(instrumentToken);

            return newIOBs;

        } catch (Exception e) {
            logger.error("Error scanning for IOBs: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Fully enriches a newly detected IOB and persists it.
     * Called after priority assignment so enhancedConfidence and isValid use the final scores.
     */
    private void persistNewIOB(InternalOrderBlock iob, List<HistoricalCandle> candles) {
        enhanceWithMarketStructure(iob, candles);
        enhanceWithVolumeProfile(iob, candles);
        enhanceWithRiskManagement(iob);

        // enhancedConfidence uses the priority-adjusted fvgValidationScore
        calculateEnhancedConfidence(iob);

        // isValid was set in validateIOB before priority assignment; refresh it now
        iob.setIsValid(iob.getEnhancedConfidence() != null && iob.getEnhancedConfidence() >= 50.0);

        iob.setDetectionAlertSent(false);
        iob.setMitigationAlertSent(false);
        iob.setTarget1AlertSent(false);
        iob.setTarget2AlertSent(false);
        iob.setTarget3AlertSent(false);

        try {
            // Alert before save so detectionAlertSent=true is persisted in one write
            sendIOBTelegramAlert(iob);
            iob.setDetectionAlertSent(true);
            iobRepository.save(iob);
            logger.info("Saved new IOB: {} at zone {}-{} (Enhanced Confidence: {}%)",
                    iob.getObType(), iob.getZoneLow(), iob.getZoneHigh(),
                    iob.getEnhancedConfidence() != null ? String.format("%.1f", iob.getEnhancedConfidence()) : "N/A");
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Race condition: another concurrent request saved the same IOB
            logger.debug("IOB already exists (concurrent save), skipping: {} at zone {}-{}",
                    iob.getObType(), iob.getZoneLow(), iob.getZoneHigh());
        }
    }

    /**
     * Persists an EARLY IOB (real-time BOS signal, BOS candle still forming).
     * Status = EARLY so getFreshIOBs() / trading logic does NOT pick it up yet.
     * Sends an "early / partial" Telegram alert so the user gets an advance notice.
     */
    private void persistEarlyIOB(InternalOrderBlock iob, List<HistoricalCandle> candles) {
        enhanceWithMarketStructure(iob, candles);
        enhanceWithVolumeProfile(iob, candles);
        enhanceWithRiskManagement(iob);
        calculateEnhancedConfidence(iob);
        iob.setIsValid(iob.getEnhancedConfidence() != null && iob.getEnhancedConfidence() >= 50.0);

        iob.setDetectionAlertSent(false);
        iob.setMitigationAlertSent(false);
        iob.setTarget1AlertSent(false);
        iob.setTarget2AlertSent(false);
        iob.setTarget3AlertSent(false);

        try {
            sendIOBEarlySignalAlert(iob);
            iob.setDetectionAlertSent(true);
            iobRepository.save(iob);
            logger.info("Saved EARLY IOB (real-time BOS): {} at zone {}-{} (Confidence: {}%)",
                    iob.getObType(), iob.getZoneLow(), iob.getZoneHigh(),
                    iob.getEnhancedConfidence() != null ? String.format("%.1f", iob.getEnhancedConfidence()) : "N/A");
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            logger.debug("EARLY IOB already exists (concurrent save), skipping: {}", iob.getIobSignature());
        }
    }

    /**
     * Upgrades an existing EARLY IOB to FRESH when the closed-candle scan confirms
     * the BOS candle has fully closed. Re-enriches with final candle data and sends
     * a "confirmed" Telegram alert. Trading logic (getFreshIOBs) picks it up after this.
     */
    private void upgradeEarlyToFresh(InternalOrderBlock earlyIOB, InternalOrderBlock closedCandle,
                                      List<HistoricalCandle> candles) {
        // Item 4: Skip upgrade if price is already too far from the OB zone.
        // If the BOS candle was a massive outlier, the risk-to-reward at confirmation entry is poor.
        double confirmDistancePct = closedCandle.getDistancePercent() != null
                ? Math.abs(closedCandle.getDistancePercent()) : 0.0;
        if (confirmDistancePct > MAX_CONFIRMED_DISTANCE_PERCENT) {
            earlyIOB.setStatus("EXPIRED");
            earlyIOB.setValidationNotes(String.format(
                    "CONFIRMED skipped — price already %.2f%% from OB zone (max %.1f%%). RR invalidated.",
                    confirmDistancePct, MAX_CONFIRMED_DISTANCE_PERCENT));
            iobRepository.save(earlyIOB);
            logger.info("EARLY IOB expired (too far at confirmation): {}% from zone, max {}%",
                    String.format("%.2f", confirmDistancePct), MAX_CONFIRMED_DISTANCE_PERCENT);
            return;
        }

        earlyIOB.setStatus("FRESH");
        earlyIOB.setDetectionTimestamp(LocalDateTime.now()); // timestamp of BOS candle close

        // Copy trade-setup fields from the freshly-computed closed-candle version
        earlyIOB.setEntryPrice(closedCandle.getEntryPrice());
        earlyIOB.setStopLoss(closedCandle.getStopLoss());
        earlyIOB.setTarget1(closedCandle.getTarget1());
        earlyIOB.setTarget2(closedCandle.getTarget2());
        earlyIOB.setTarget3(closedCandle.getTarget3());
        earlyIOB.setRiskRewardRatio(closedCandle.getRiskRewardRatio());
        earlyIOB.setSignalConfidence(closedCandle.getSignalConfidence());
        earlyIOB.setIsValid(closedCandle.getIsValid());
        earlyIOB.setValidationNotes(closedCandle.getValidationNotes());

        // Re-run full enrichment against the now-complete candle set
        enhanceWithMarketStructure(earlyIOB, candles);
        enhanceWithVolumeProfile(earlyIOB, candles);
        enhanceWithRiskManagement(earlyIOB);
        calculateEnhancedConfidence(earlyIOB);
        earlyIOB.setIsValid(earlyIOB.getEnhancedConfidence() != null && earlyIOB.getEnhancedConfidence() >= 50.0);

        try {
            sendIOBConfirmationAlert(earlyIOB);
            earlyIOB.setDetectionAlertSent(true);
            iobRepository.save(earlyIOB);
            logger.info("EARLY → FRESH confirmed: {} at zone {}-{} (Confidence: {}%)",
                    earlyIOB.getObType(), earlyIOB.getZoneLow(), earlyIOB.getZoneHigh(),
                    earlyIOB.getEnhancedConfidence() != null ? String.format("%.1f", earlyIOB.getEnhancedConfidence()) : "N/A");
        } catch (Exception e) {
            logger.error("Error upgrading EARLY IOB to FRESH: {}", e.getMessage(), e);
        }
    }

    /**
     * Expires EARLY IOBs that are older than EARLY_IOB_EXPIRY_MINUTES without having been upgraded.
     * These represent wick-trap signals: real-time BOS fired but the BOS candle closed below the swing.
     * Expiring them unblocks future patterns on the same OB candle from being detected.
     */
    private void expireStaleEarlyIOBs(Long instrumentToken) {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusMinutes(EARLY_IOB_EXPIRY_MINUTES);
            List<InternalOrderBlock> stale = iobRepository.findStaleEarlyIOBs(instrumentToken, cutoff);
            if (stale.isEmpty()) return;
            stale.forEach(iob -> {
                iob.setStatus("EXPIRED");
                iob.setValidationNotes("EARLY signal expired — BOS candle closed without confirming swing cross (wick trap)");
            });
            iobRepository.saveAll(stale);
            logger.info("Expired {} stale EARLY IOB(s) for token {} (wick trap confirmed)", stale.size(), instrumentToken);
        } catch (Exception e) {
            logger.debug("Error expiring stale EARLY IOBs: {}", e.getMessage());
        }
    }

    /**
     * Sends a "⚡ EARLY SIGNAL" Telegram alert for a real-time BOS detection.
     * Visually distinct from the confirmed FRESH alert so the user knows BOS candle is still forming.
     */
    private void sendIOBEarlySignalAlert(InternalOrderBlock iob) {
        sendIOBTelegramAlertWithType(iob, "EARLY");
    }

    /**
     * Sends a "✅ CONFIRMED SIGNAL" Telegram alert after BOS candle fully closes.
     */
    private void sendIOBConfirmationAlert(InternalOrderBlock iob) {
        sendIOBTelegramAlertWithType(iob, "CONFIRMED");
    }

    /**
     * Core alert sender with a type label ("EARLY" / "CONFIRMED" / "DETECTED").
     * Used by sendIOBTelegramAlert, sendIOBEarlySignalAlert, sendIOBConfirmationAlert.
     */
    private void sendIOBTelegramAlertWithType(InternalOrderBlock iob, String alertType) {
        if (telegramNotificationService == null || !telegramNotificationService.isConfigured()) return;
        if (!telegramNotificationService.isAlertTypeEnabled("TRADE", "IOB")) return;

        String instrumentName = iob.getInstrumentName();
        if (instrumentName == null || !instrumentName.contains("NIFTY")) return;

        double minConfidence = telegramNotificationService.getTradeAlertMinConfidence();
        Double confidence = iob.getSignalConfidence();
        if (confidence == null || confidence <= minConfidence) return;

        try {
            String direction = iob.getObType().contains("BULLISH") ? "BULLISH" : "BEARISH";
            String directionEmoji = direction.equals("BULLISH") ? "🟢" : "🔴";

            String typeLabel;
            if ("EARLY".equals(alertType)) {
                typeLabel = "⚡ EARLY SIGNAL";
            } else if ("CONFIRMED".equals(alertType)) {
                typeLabel = "✅ CONFIRMED SIGNAL";
            } else {
                typeLabel = directionEmoji + " IOB Signal";
            }

            String title = String.format("%s - %s %s", typeLabel, direction, instrumentName);
            String message = String.format("%s IOB on %s\nZone: ₹%.2f - ₹%.2f | Current: ₹%.2f%s",
                    direction, instrumentName,
                    iob.getZoneLow(), iob.getZoneHigh(),
                    iob.getCurrentPrice() != null ? iob.getCurrentPrice() : 0.0,
                    "EARLY".equals(alertType) ? "\n⚠️ BOS candle still forming — await confirmation" : "");

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("Status", "EARLY".equals(alertType) ? "⚡ Partial (BOS forming)" : "✅ Confirmed (BOS closed)");
            data.put("Instrument", instrumentName);
            data.put("Type", iob.getObType());
            data.put("Trade", iob.getTradeDirection() != null ? iob.getTradeDirection() : direction);
            data.put("Zone", String.format("₹%.2f - ₹%.2f", iob.getZoneLow(), iob.getZoneHigh()));
            data.put("Confidence", String.format("%.1f%%", confidence));
            if (iob.getEntryPrice() != null) data.put("Entry", String.format("₹%.2f", iob.getEntryPrice()));
            if (iob.getStopLoss() != null) data.put("Stop-Loss", String.format("₹%.2f", iob.getStopLoss()));
            if (iob.getTarget1() != null) data.put("Target 1", String.format("₹%.2f", iob.getTarget1()));
            if (iob.getTarget2() != null) data.put("Target 2", String.format("₹%.2f", iob.getTarget2()));
            if (iob.getTarget3() != null) data.put("Target 3", String.format("₹%.2f", iob.getTarget3()));
            if (iob.getRiskRewardRatio() != null) data.put("Risk:Reward", String.format("1:%.2f", iob.getRiskRewardRatio()));
            String timeframeDisplay = formatTimeframeWithRange(iob.getTimeframe(), iob.getObCandleTime());
            data.put("Timeframe", timeframeDisplay);
            if (iob.getDetectionTimestamp() != null) {
                data.put("Detection Time", iob.getDetectionTimestamp().format(DATETIME_FORMATTER));
            }

            telegramNotificationService.sendTradeAlertAsync(title, message, data);
            logger.info("Telegram IOB {} alert sent for {} {} (confidence {}%)",
                    alertType, instrumentName, direction, String.format("%.1f", confidence));
        } catch (Exception e) {
            logger.warn("Failed to send IOB {} Telegram alert for IOB {}", alertType, iob.getId(), e);
        }
    }

    /**
     * Detect Internal Order Blocks for one direction.
     * Bullish: last bearish candle before a bullish BOS (broken swing high).
     * Bearish: last bullish candle before a bearish BOS (broken swing low).
     */
    private List<InternalOrderBlock> detectIOBs(boolean bullish, Long instrumentToken, String timeframe,
            List<HistoricalCandle> candles, List<SwingPoint> swingHighs, List<SwingPoint> swingLows,
            double currentPrice) {

        List<InternalOrderBlock> iobs = new ArrayList<>();

        for (int i = SWING_LOOKBACK + 5; i < candles.size() - 1; i++) {
            HistoricalCandle bosCandle = candles.get(i);

            SwingPoint brokenLevel = bullish
                    ? findBrokenSwingHigh(swingHighs, bosCandle, i)
                    : findBrokenSwingLow(swingLows, bosCandle, i);
            if (brokenLevel == null) continue;

            int obIndex = bullish
                    ? findLastBearishCandle(candles, brokenLevel.index, i)
                    : findLastBullishCandle(candles, brokenLevel.index, i);
            if (obIndex == -1) continue;

            if (!hasValidDisplacement(candles, obIndex, i, bullish)) continue;

            InternalOrderBlock iob = bullish
                    ? createBullishIOB(instrumentToken, timeframe, candles.get(obIndex), obIndex, candles, currentPrice, brokenLevel, swingHighs, swingLows)
                    : createBearishIOB(instrumentToken, timeframe, candles.get(obIndex), obIndex, candles, currentPrice, brokenLevel, swingHighs, swingLows);

            if (iob.getIsValid()) {
                iobs.add(iob);
            }
        }

        return iobs;
    }

    /**
     * Supplemental real-time BOS detection.
     * Called when the closed-candle scan found nothing. Checks whether the current live
     * NIFTY price has already crossed a swing level for a valid OB+displacement pattern,
     * even before the BOS candle has fully closed.
     *
     * Reduces detection lag from 15 min (3 closed candles) to ~10 min average.
     *
     * Wick-trap guards:
     *   1. 10-point buffer past the swing — filters noise / momentary spikes.
     *   2. Time-weighted RVOL ≥ 1.2×: pace = (inProgressVolume / elapsedFraction) / avgVolume.
     *      A wick fuelled by near-zero volume yields RVOL ≪ 1 and is rejected.
     *      A genuine institutional thrust yields RVOL ≫ 1.2 even in the first minute.
     */
    private List<InternalOrderBlock> detectRealtimeBOS(Long instrumentToken, String timeframe,
            List<HistoricalCandle> candles, List<SwingPoint> swingHighs, List<SwingPoint> swingLows,
            double liveLTP) {

        List<InternalOrderBlock> result = new ArrayList<>();
        int size = candles.size();

        // Check last 3 closed positions as OB candidates.
        // obIndex+1 = displacement must be closed (index <= size-2), so obIndex <= size-3.
        int scanFrom = Math.max(SWING_LOOKBACK + 5, size - 4);
        int scanTo   = size - 3;
        if (scanFrom > scanTo) return result;

        // --- Time-weighted RVOL filter (Gemini: RVOL > 1.2×) ---
        // candles.get(size-1) = in-progress (current) candle from Kite.
        // Elapsed fraction = seconds since candle start / 300 (5-min candle).
        // RVOL = (inProgressVolume / elapsedFraction) / avgClosedVolume
        // A wick spike on near-zero volume yields RVOL << 1; genuine institutional thrust yields RVOL >> 1.2.
        long inProgressVolume = candles.get(size - 1).getVolume();
        long avgClosedVolume  = computeAvgVolume(candles, size - 1, 20);
        double rvolRatio      = computeRvolRatio(candles, size, inProgressVolume, avgClosedVolume);
        boolean rvolElevated  = rvolRatio >= REALTIME_MIN_RVOL;
        if (!rvolElevated) {
            logger.debug("Real-time BOS skipped: RVOL {}x < {}x threshold (inProgress={}, avg={})",
                    String.format("%.2f", rvolRatio), String.format("%.1f", REALTIME_MIN_RVOL),
                    inProgressVolume, avgClosedVolume);
            return result;
        }

        for (boolean bullish : new boolean[]{true, false}) {
            for (int obIndex = scanFrom; obIndex <= scanTo; obIndex++) {
                HistoricalCandle obCandle = candles.get(obIndex);

                // OB must be correct direction
                boolean obCorrectType = bullish
                        ? obCandle.getClose() < obCandle.getOpen()  // bearish OB for bullish IOB
                        : obCandle.getClose() > obCandle.getOpen(); // bullish OB for bearish IOB
                if (!obCorrectType) continue;

                // Displacement: closed, strong body, correct direction, body >= OB body, live price confirms direction
                if (!hasValidDisplacementRealtimeBOS(candles, obIndex, liveLTP, bullish)) continue;

                // Swing that live price has already crossed (with 10-point buffer)
                SwingPoint crossedSwing = bullish
                        ? findCrossedSwingHighRealtime(swingHighs, obIndex, liveLTP)
                        : findCrossedSwingLowRealtime(swingLows, obIndex, liveLTP);
                if (crossedSwing == null) continue;

                InternalOrderBlock iob = bullish
                        ? createBullishIOB(instrumentToken, timeframe, obCandle, obIndex, candles, liveLTP, crossedSwing, swingHighs, swingLows)
                        : createBearishIOB(instrumentToken, timeframe, obCandle, obIndex, candles, liveLTP, crossedSwing, swingHighs, swingLows);

                if (iob.getIsValid()) {
                    iob.setStatus("EARLY"); // Real-time: BOS candle still forming — mark as partial signal
                    logger.info("Real-time BOS (EARLY): {} IOB at zone {}-{} (LTP={}, swing={}, RVOL={}x)",
                            iob.getObType(), iob.getZoneLow(), iob.getZoneHigh(),
                            liveLTP, crossedSwing.price, String.format("%.2f", rvolRatio));
                    result.add(iob);
                }
            }
        }
        return result;
    }

    /**
     * Compute average volume over the last {@code count} closed candles ending before {@code endIndex}.
     * endIndex is exclusive (i.e. the in-progress candle sits at endIndex).
     */
    private long computeAvgVolume(List<HistoricalCandle> candles, int endIndex, int count) {
        int from = Math.max(0, endIndex - count);
        int to   = endIndex; // exclusive
        if (from >= to) return 0;
        long sum = 0;
        for (int i = from; i < to; i++) sum += candles.get(i).getVolume();
        return sum / (to - from);
    }

    /**
     * Compute time-weighted Relative Volume (RVOL) for the in-progress candle.
     *
     * RVOL = (inProgressVolume / elapsedFraction) / avgClosedVolume
     *
     * elapsedFraction = seconds elapsed since candle start / 300 (5-min candle).
     * Computed from the previous closed candle's timestamp + 5 minutes.
     *
     * If elapsed time is < 10% of candle (< 30 sec) — too early, return 0 to skip.
     * avgClosedVolume = 0 → return 0 (no baseline, skip).
     */
    private double computeRvolRatio(List<HistoricalCandle> candles, int size,
                                     long inProgressVolume, long avgClosedVolume) {
        if (avgClosedVolume <= 0) return 0.0;

        try {
            // Previous closed candle's timestamp + 5 min = start of current candle
            HistoricalCandle lastClosed = candles.get(size - 2);
            LocalDateTime candleStart = parseTimestamp(lastClosed.getTimestamp()).plusMinutes(5);
            long elapsedSeconds = ChronoUnit.SECONDS.between(candleStart, LocalDateTime.now());
            if (elapsedSeconds < 30) return 0.0; // too early in candle

            double elapsedFraction = Math.min(1.0, elapsedSeconds / 300.0); // 5 min = 300 s
            double pacedVolume = inProgressVolume / elapsedFraction;
            return pacedVolume / avgClosedVolume;
        } catch (Exception e) {
            logger.debug("RVOL computation failed, falling back to raw ratio: {}", e.getMessage());
            // Fallback: raw ratio (assume 40% through candle = midpoint)
            return avgClosedVolume > 0 ? (inProgressVolume / (avgClosedVolume * 0.40)) : 0.0;
        }
    }

    /**
     * Displacement validation for real-time BOS.
     * Conditions 1 & 2 are identical to hasValidDisplacement (closed candles).
     * Condition 3 uses live LTP instead of a closed BOS candle close.
     */
    private boolean hasValidDisplacementRealtimeBOS(List<HistoricalCandle> candles, int obIndex,
                                                     double liveLTP, boolean bullish) {
        if (obIndex + 1 >= candles.size() - 1) return false; // displacement must be a closed candle

        HistoricalCandle obCandle           = candles.get(obIndex);
        HistoricalCandle displacementCandle = candles.get(obIndex + 1);

        double dispBody  = Math.abs(displacementCandle.getClose() - displacementCandle.getOpen());
        double dispRange = displacementCandle.getHigh() - displacementCandle.getLow();
        if (dispRange == 0) return false;

        boolean strongBody       = dispBody / dispRange >= MIN_DISPLACEMENT_BODY_PERCENT;
        boolean correctDirection = bullish
                ? displacementCandle.getClose() > displacementCandle.getOpen()
                : displacementCandle.getClose() < displacementCandle.getOpen();
        if (!strongBody || !correctDirection) return false;

        double obBody = Math.abs(obCandle.getClose() - obCandle.getOpen());
        if (dispBody < obBody) return false;

        // Condition 3: live price confirms direction vs OB close
        double netMove = liveLTP - obCandle.getClose();
        return bullish ? netMove > 0 : netMove < 0;
    }

    /** 10-point buffer above swing high — requires LTP to be meaningfully past the level, not just a wick touch. */
    private SwingPoint findCrossedSwingHighRealtime(List<SwingPoint> swingHighs, int obIndex, double liveLTP) {
        for (int k = swingHighs.size() - 1; k >= 0; k--) {
            SwingPoint sh = swingHighs.get(k);
            if (sh.index < obIndex && sh.index > obIndex - 30) {
                if (liveLTP > sh.price + 10.0) return sh; // 10-point buffer (doubled from 5) — wick-trap guard
            }
        }
        return null;
    }

    /** 10-point buffer below swing low — symmetric wick-trap guard for bearish BOS. */
    private SwingPoint findCrossedSwingLowRealtime(List<SwingPoint> swingLows, int obIndex, double liveLTP) {
        for (int k = swingLows.size() - 1; k >= 0; k--) {
            SwingPoint sl = swingLows.get(k);
            if (sl.index < obIndex && sl.index > obIndex - 30) {
                if (liveLTP < sl.price - 10.0) return sl; // 10-point buffer (doubled from 5) — wick-trap guard
            }
        }
        return null;
    }

    /**
     * Create a Bullish IOB entity
     */
    private InternalOrderBlock createBullishIOB(Long instrumentToken, String timeframe,
            HistoricalCandle obCandle, int obIndex, List<HistoricalCandle> candles,
            Double currentPrice, SwingPoint bosLevel,
            List<SwingPoint> swingHighs, List<SwingPoint> swingLows) {

        InternalOrderBlock iob = InternalOrderBlock.builder()
                .instrumentToken(instrumentToken)
                .instrumentName(getInstrumentName(instrumentToken))
                .timeframe(timeframe)
                .detectionTimestamp(LocalDateTime.now())
                .obCandleTime(parseTimestamp(obCandle.getTimestamp()))
                .obType("BULLISH_IOB")
                .obHigh(obCandle.getHigh())
                .obLow(obCandle.getLow())
                .obOpen(obCandle.getOpen())
                .obClose(obCandle.getClose())
                .zoneHigh(obCandle.getHigh())
                .zoneLow(obCandle.getLow())
                .zoneMidpoint((obCandle.getHigh() + obCandle.getLow()) / 2)
                .currentPrice(currentPrice)
                .bosLevel(bosLevel.price)
                .bosType("BULLISH_BOS")
                .tradeDirection("LONG")
                .status("FRESH")
                .tradeTaken(false)
                .build();

        // Distance to zone: how far price currently is above the IOB zone (always positive after a bullish BOS)
        double distanceToZone = currentPrice - iob.getZoneHigh();
        iob.setDistanceToZone(distanceToZone);
        iob.setDistancePercent((distanceToZone / currentPrice) * 100);

        // Check for FVG (Fair Value Gap) and validate with 6 factors
        checkForFVG(iob, candles, obIndex, true, swingHighs, swingLows);

        // Calculate trade setup
        calculateBullishTradeSetup(iob, candles);

        // Validate IOB
        validateIOB(iob, currentPrice);

        return iob;
    }

    /**
     * Create a Bearish IOB entity
     */
    private InternalOrderBlock createBearishIOB(Long instrumentToken, String timeframe,
            HistoricalCandle obCandle, int obIndex, List<HistoricalCandle> candles,
            Double currentPrice, SwingPoint bosLevel,
            List<SwingPoint> swingHighs, List<SwingPoint> swingLows) {

        InternalOrderBlock iob = InternalOrderBlock.builder()
                .instrumentToken(instrumentToken)
                .instrumentName(getInstrumentName(instrumentToken))
                .timeframe(timeframe)
                .detectionTimestamp(LocalDateTime.now())
                .obCandleTime(parseTimestamp(obCandle.getTimestamp()))
                .obType("BEARISH_IOB")
                .obHigh(obCandle.getHigh())
                .obLow(obCandle.getLow())
                .obOpen(obCandle.getOpen())
                .obClose(obCandle.getClose())
                .zoneHigh(obCandle.getHigh())
                .zoneLow(obCandle.getLow())
                .zoneMidpoint((obCandle.getHigh() + obCandle.getLow()) / 2)
                .currentPrice(currentPrice)
                .bosLevel(bosLevel.price)
                .bosType("BEARISH_BOS")
                .tradeDirection("SHORT")
                .status("FRESH")
                .tradeTaken(false)
                .build();

        // Distance to zone: how far price currently is below the IOB zone (always positive after a bearish BOS)
        double distanceToZone = iob.getZoneLow() - currentPrice;
        iob.setDistanceToZone(distanceToZone);
        iob.setDistancePercent((distanceToZone / currentPrice) * 100);

        // Check for FVG (Fair Value Gap) and validate with 6 factors
        checkForFVG(iob, candles, obIndex, false, swingHighs, swingLows);

        // Calculate trade setup
        calculateBearishTradeSetup(iob, candles);

        // Validate IOB
        validateIOB(iob, currentPrice);

        return iob;
    }

    /**
     * Calculate trade setup for Bullish IOB
     */
    private void calculateBullishTradeSetup(InternalOrderBlock iob, List<HistoricalCandle> candles) {
        // Entry at zone midpoint or zone high
        double entryPrice = iob.getZoneMidpoint();
        iob.setEntryPrice(entryPrice);

        // Stop loss below zone low with buffer
        double stopLoss = iob.getZoneLow() - (iob.getZoneLow() * IOB_ZONE_BUFFER_PERCENT / 100.0);
        iob.setStopLoss(stopLoss);

        double riskPoints = entryPrice - stopLoss;

        // Targets based on risk-reward ratios
        iob.setTarget1(entryPrice + riskPoints * 1.5);
        iob.setTarget2(entryPrice + riskPoints * 2.5);
        iob.setTarget3(entryPrice + riskPoints * 4.0);

        iob.setRiskRewardRatio(2.5);
    }

    /**
     * Calculate trade setup for Bearish IOB
     */
    private void calculateBearishTradeSetup(InternalOrderBlock iob, List<HistoricalCandle> candles) {
        // Entry at zone midpoint or zone low
        double entryPrice = iob.getZoneMidpoint();
        iob.setEntryPrice(entryPrice);

        // Stop loss above zone high with buffer
        double stopLoss = iob.getZoneHigh() + (iob.getZoneHigh() * IOB_ZONE_BUFFER_PERCENT / 100.0);
        iob.setStopLoss(stopLoss);

        double riskPoints = stopLoss - entryPrice;

        // Targets based on risk-reward ratios
        iob.setTarget1(entryPrice - riskPoints * 1.5);
        iob.setTarget2(entryPrice - riskPoints * 2.5);
        iob.setTarget3(entryPrice - riskPoints * 4.0);

        iob.setRiskRewardRatio(2.5);
    }

    /**
     * Validate IOB for trading
     */
    private void validateIOB(InternalOrderBlock iob, Double currentPrice) {
        StringBuilder notes = new StringBuilder();
        double confidence = 70.0;

        // Check if zone is reasonable size (not too wide)
        double zoneSize = iob.getZoneHigh() - iob.getZoneLow();
        double zoneSizePercent = (zoneSize / currentPrice) * 100;

        if (zoneSizePercent > 1.0) {
            notes.append("Zone too wide (").append(String.format("%.2f", zoneSizePercent)).append("%). ");
            confidence -= 15;
        }

        if (zoneSizePercent < 0.05) {
            notes.append("Zone too narrow. ");
            confidence -= 10;
        }

        // Check distance from current price — graduated penalty; hard-invalid beyond 5%
        double distancePercent = Math.abs(iob.getDistancePercent());
        if (distancePercent > 5.0) {
            notes.append("Zone unreachable — price too far (").append(String.format("%.2f", distancePercent)).append("%). ");
            confidence = 0; // force invalid — IOB is stale/untradeable at this distance
        } else if (distancePercent > 2.0) {
            notes.append("Price far from zone (").append(String.format("%.2f", distancePercent)).append("%). ");
            confidence -= 20;
        }

        // Boost confidence based on FVG validation (graduated scoring)
        if (Boolean.TRUE.equals(iob.getHasFvg())) {
            if (Boolean.TRUE.equals(iob.getFvgValid())) {
                // Valid FVG: graduated boost based on validation score (up to +20)
                double fvgScore = iob.getFvgValidationScore() != null ? iob.getFvgValidationScore() : 50.0;
                double fvgBoost = (fvgScore / 100.0) * 20.0;
                confidence += fvgBoost;
                notes.append(String.format("Valid FVG (Score: %.0f%%, +%.1f). ", fvgScore, fvgBoost));
            } else {
                // FVG present but invalid: smaller boost
                notes.append("FVG present but invalid. ");
                confidence += 5;
            }
        }

        // Check if price is already inside or past the zone
        if ("BULLISH_IOB".equals(iob.getObType())) {
            if (currentPrice <= iob.getZoneLow()) {
                notes.append("Price below zone - may be mitigated. ");
                confidence -= 20;
            }
        } else {
            if (currentPrice >= iob.getZoneHigh()) {
                notes.append("Price above zone - may be mitigated. ");
                confidence -= 20;
            }
        }

        confidence = Math.max(0, Math.min(100, confidence));

        iob.setIsValid(confidence >= 50);
        iob.setSignalConfidence(confidence);
        iob.setValidationNotes(notes.toString());
    }

    // ==================== Helper Methods ====================

    /**
     * Identify swing highs in the candle data.
     * Uses strict inequality (>) so equal-high double tops qualify as swing points.
     */
    private List<SwingPoint> identifySwingHighs(List<HistoricalCandle> candles) {
        List<SwingPoint> swingHighs = new ArrayList<>();

        for (int i = SWING_LOOKBACK; i < candles.size() - SWING_LOOKBACK; i++) {
            double high = candles.get(i).getHigh();
            boolean isSwingHigh = true;

            for (int j = 1; j <= SWING_LOOKBACK; j++) {
                if (candles.get(i - j).getHigh() > high || candles.get(i + j).getHigh() > high) {
                    isSwingHigh = false;
                    break;
                }
            }

            if (isSwingHigh) {
                swingHighs.add(new SwingPoint(i, high));
            }
        }

        return swingHighs;
    }

    /**
     * Identify swing lows in the candle data.
     * Uses strict inequality (<) so equal-low double bottoms qualify as swing points.
     */
    private List<SwingPoint> identifySwingLows(List<HistoricalCandle> candles) {
        List<SwingPoint> swingLows = new ArrayList<>();

        for (int i = SWING_LOOKBACK; i < candles.size() - SWING_LOOKBACK; i++) {
            double low = candles.get(i).getLow();
            boolean isSwingLow = true;

            for (int j = 1; j <= SWING_LOOKBACK; j++) {
                if (candles.get(i - j).getLow() < low || candles.get(i + j).getLow() < low) {
                    isSwingLow = false;
                    break;
                }
            }

            if (isSwingLow) {
                swingLows.add(new SwingPoint(i, low));
            }
        }

        return swingLows;
    }

    /**
     * Find swing high that was broken by the current candle.
     * Iterates in reverse (most-recent first) so the nearest broken swing high is returned,
     * not the oldest one in the window — which would anchor the BOS to stale structure.
     */
    private SwingPoint findBrokenSwingHigh(List<SwingPoint> swingHighs, HistoricalCandle candle, int currentIndex) {
        for (int k = swingHighs.size() - 1; k >= 0; k--) {
            SwingPoint sh = swingHighs.get(k);
            // Swing high must be before current candle and within reasonable range
            if (sh.index < currentIndex && sh.index > currentIndex - 30) {
                if (candle.getClose() > sh.price && candle.getOpen() < sh.price) {
                    return sh;
                }
            }
        }
        return null;
    }

    /**
     * Find swing low that was broken by the current candle.
     * Iterates in reverse (most-recent first) so the nearest broken swing low is returned,
     * not the oldest one in the window — which would anchor the BOS to stale structure.
     */
    private SwingPoint findBrokenSwingLow(List<SwingPoint> swingLows, HistoricalCandle candle, int currentIndex) {
        for (int k = swingLows.size() - 1; k >= 0; k--) {
            SwingPoint sl = swingLows.get(k);
            // Swing low must be before current candle and within reasonable range
            if (sl.index < currentIndex && sl.index > currentIndex - 30) {
                if (candle.getClose() < sl.price && candle.getOpen() > sl.price) {
                    return sl;
                }
            }
        }
        return null;
    }

    /**
     * Find the last bearish candle before a bullish move
     */
    private int findLastBearishCandle(List<HistoricalCandle> candles, int startIndex, int endIndex) {
        for (int i = endIndex - 1; i >= startIndex; i--) {
            HistoricalCandle candle = candles.get(i);
            if (candle.getClose() < candle.getOpen()) { // Bearish candle
                return i;
            }
        }
        return -1;
    }

    /**
     * Find the last bullish candle before a bearish move
     */
    private int findLastBullishCandle(List<HistoricalCandle> candles, int startIndex, int endIndex) {
        for (int i = endIndex - 1; i >= startIndex; i--) {
            HistoricalCandle candle = candles.get(i);
            if (candle.getClose() > candle.getOpen()) { // Bullish candle
                return i;
            }
        }
        return -1;
    }

    /**
     * Check if there's valid displacement after the OB candle.
     *
     * Three conditions must all pass:
     * 1. Displacement candle (obIndex+1) is in the correct direction with a strong body (≥ MIN_DISPLACEMENT_BODY_PERCENT).
     * 2. Displacement body ≥ OB body — the impulse must overpower the OB's own pressure.
     * 3. Net move from OB close to BOS close confirms direction — end-to-end validation.
     */
    private boolean hasValidDisplacement(List<HistoricalCandle> candles, int obIndex, int bosIndex, boolean bullish) {
        if (bosIndex <= obIndex + 1) return false;

        HistoricalCandle obCandle           = candles.get(obIndex);
        HistoricalCandle displacementCandle = candles.get(obIndex + 1);
        HistoricalCandle bosCandle          = candles.get(bosIndex);

        // 1. Strong-body displacement candle in the correct direction
        double dispBody  = Math.abs(displacementCandle.getClose() - displacementCandle.getOpen());
        double dispRange = displacementCandle.getHigh() - displacementCandle.getLow();
        if (dispRange == 0) return false;

        boolean strongBody = dispBody / dispRange >= MIN_DISPLACEMENT_BODY_PERCENT;
        boolean correctDirection = bullish
                ? displacementCandle.getClose() > displacementCandle.getOpen()
                : displacementCandle.getClose() < displacementCandle.getOpen();
        if (!strongBody || !correctDirection) return false;

        // 2. Displacement body must be at least as large as the OB candle's body
        double obBody = Math.abs(obCandle.getClose() - obCandle.getOpen());
        if (dispBody < obBody) return false;

        // 3. Net move from OB close to BOS close must confirm direction
        double netMove = bosCandle.getClose() - obCandle.getClose();
        return bullish ? netMove > 0 : netMove < 0;
    }

    /**
     * Check for Fair Value Gap near the IOB and validate with 6 factors
     */
    private void checkForFVG(InternalOrderBlock iob, List<HistoricalCandle> candles, int obIndex, boolean bullish,
                             List<SwingPoint> swingHighs, List<SwingPoint> swingLows) {
        if (obIndex + 2 >= candles.size()) {
            iob.setHasFvg(false);
            iob.setFvgValid(false);
            return;
        }

        HistoricalCandle candle1 = candles.get(obIndex);
        HistoricalCandle candle3 = candles.get(obIndex + 2);

        double fvgLow, fvgHigh;
        if (bullish) {
            // Bullish FVG: gap between candle1.high and candle3.low
            if (candle3.getLow() <= candle1.getHigh()) {
                iob.setHasFvg(false);
                iob.setFvgValid(false);
                return;
            }
            fvgLow  = candle1.getHigh();
            fvgHigh = candle3.getLow();
        } else {
            // Bearish FVG: gap between candle3.high and candle1.low
            if (candle3.getHigh() >= candle1.getLow()) {
                iob.setHasFvg(false);
                iob.setFvgValid(false);
                return;
            }
            fvgHigh = candle1.getLow();
            fvgLow  = candle3.getHigh();
        }

        // Reject sub-threshold FVGs — gaps smaller than MIN_FVG_SIZE_PERCENT of price are noise
        double minSize = candle1.getHigh() * MIN_FVG_SIZE_PERCENT / 100.0;
        if (fvgHigh - fvgLow < minSize) {
            iob.setHasFvg(false);
            iob.setFvgValid(false);
            return;
        }

        iob.setHasFvg(true);
        iob.setFvgLow(fvgLow);
        iob.setFvgHigh(fvgHigh);

        // Perform 6-factor FVG validation
        validateFVG(iob, candles, obIndex, bullish, swingHighs, swingLows);
    }

    /**
     * Comprehensive FVG validation using 6 factors:
     * 1. Unmitigated - FVG zone not tested/filled since creation
     * 2. Candle Reaction - Reaction candle closes inside FVG or in gap direction
     * 3. Confluence with S/R - FVG overlaps with prior support/resistance
     * 4. Priority by Position - Lowest bullish FVG = highest priority, highest bearish = highest
     * 5. Gann Box Position - Bullish FVG in lower portion (0-0.5), bearish in upper (0.5-1)
     * 6. BOS Before Gap - FVG must form after a Break of Structure
     */
    private void validateFVG(InternalOrderBlock iob, List<HistoricalCandle> candles, int obIndex,
                             boolean bullish, List<SwingPoint> swingHighs, List<SwingPoint> swingLows) {

        double fvgHigh = iob.getFvgHigh();
        double fvgLow = iob.getFvgLow();
        int fvgFormationIndex = obIndex + 2; // FVG completes at candle 3
        int factorsPassed = 0;
        StringBuilder details = new StringBuilder();

        // ========== Factor 1: Unmitigated ==========
        // Check if any candle after FVG formation has closed or wicked fully into the FVG zone
        boolean unmitigated = true;
        for (int i = fvgFormationIndex + 1; i < candles.size(); i++) {
            HistoricalCandle c = candles.get(i);
            // Check if candle close is inside FVG zone
            boolean closeInZone = c.getClose() >= fvgLow && c.getClose() <= fvgHigh;
            // Check if wick fully enters zone (candle low <= fvgLow for bullish means it went through)
            boolean wickThroughZone;
            if (bullish) {
                // For bullish FVG, price dropping below fvgLow means fully mitigated
                wickThroughZone = c.getLow() <= fvgLow;
            } else {
                // For bearish FVG, price rising above fvgHigh means fully mitigated
                wickThroughZone = c.getHigh() >= fvgHigh;
            }
            if (closeInZone || wickThroughZone) {
                unmitigated = false;
                break;
            }
        }
        iob.setFvgUnmitigated(unmitigated);
        if (unmitigated) {
            factorsPassed++;
            details.append("Unmitigated ✅ | ");
        } else {
            details.append("Unmitigated ❌ | ");
        }

        // ========== Factor 2: Candle Reaction ==========
        // When price retraces to the FVG, check if the reaction candle closes properly
        boolean candleReactionValid = false;
        boolean noRetraceYet = true; // tracked in the same pass to avoid a second scan
        // Look for the first candle that touches the FVG zone after formation
        for (int i = fvgFormationIndex + 1; i < candles.size(); i++) {
            HistoricalCandle c = candles.get(i);
            // Track whether the zone boundary has been reached at all (broader check)
            if (bullish && c.getLow() <= fvgHigh) noRetraceYet = false;
            if (!bullish && c.getHigh() >= fvgLow) noRetraceYet = false;

            boolean touchesZone;
            if (bullish) {
                // Price retracing down to touch FVG: candle low touches or enters the zone
                touchesZone = c.getLow() <= fvgHigh && c.getLow() >= fvgLow;
            } else {
                // Price retracing up to touch FVG: candle high touches or enters the zone
                touchesZone = c.getHigh() >= fvgLow && c.getHigh() <= fvgHigh;
            }

            if (touchesZone) {
                // Valid reaction: candle closes at or above fvgLow (bullish) / at or below fvgHigh (bearish)
                // A close through the far side of the zone invalidates the reaction
                candleReactionValid = bullish ? c.getClose() >= fvgLow : c.getClose() <= fvgHigh;
                break; // Only check first retrace candle
            }
        }
        if (noRetraceYet) {
            candleReactionValid = true; // Not yet tested = valid by default
        }

        iob.setFvgCandleReactionValid(candleReactionValid);
        if (candleReactionValid) {
            factorsPassed++;
            details.append("Candle Reaction ✅ | ");
        } else {
            details.append("Candle Reaction ❌ | ");
        }

        // ========== Factor 3: Confluence with S/R ==========
        // Any prior swing point (high or low) within the buffered FVG zone qualifies.
        // Bullish IOBs benefit from support (swing lows), bearish from resistance (swing highs),
        // but a broken level of either type can flip roles — check both lists in both cases.
        double fvgZoneBuffer = (fvgHigh - fvgLow) * 0.5; // 50% buffer for near-overlap
        double checkLow = fvgLow - fvgZoneBuffer;
        double checkHigh = fvgHigh + fvgZoneBuffer;
        boolean srConfluence = anySwingInRange(swingLows, obIndex, checkLow, checkHigh)
                            || anySwingInRange(swingHighs, obIndex, checkLow, checkHigh);

        iob.setFvgSrConfluence(srConfluence);
        if (srConfluence) {
            factorsPassed++;
            details.append("S/R Confluence ✅ | ");
        } else {
            details.append("S/R Confluence ❌ | ");
        }

        // ========== Factor 4: Priority by Position ==========
        // This is set as a post-pass in scanForIOBs() after all IOBs are detected
        // For now, initialize priority to 0 (will be updated later)
        iob.setFvgPriority(0);
        // We count this factor during the post-pass; for scoring we'll give benefit of doubt
        details.append("Priority: Pending | ");

        // ========== Factor 5: Gann Box Position ==========
        // Simulate Gann Box: find recent move range (lookback ~30 candles)
        // For bullish: draw from low-to-high; FVG must be in lower portion (0-0.5)
        // For bearish: draw from high-to-low; FVG must be in upper portion (0.5-1)
        boolean gannBoxValid = false;
        int gannLookback = Math.min(50, obIndex); // Look back up to 50 candles
        if (gannLookback > 5) {
            double rangeHigh = Double.NEGATIVE_INFINITY;
            double rangeLow = Double.MAX_VALUE;

            for (int i = obIndex - gannLookback; i < obIndex; i++) {
                if (i < 0) continue;
                HistoricalCandle c = candles.get(i);
                rangeHigh = Math.max(rangeHigh, c.getHigh());
                rangeLow = Math.min(rangeLow, c.getLow());
            }

            double totalRange = rangeHigh - rangeLow;
            if (totalRange > 0) {
                double fvgMidpoint = (fvgHigh + fvgLow) / 2.0;
                // Normalize FVG midpoint to 0-1 within the Gann range
                // 0 = rangeLow (bottom), 1 = rangeHigh (top)
                double normalizedPosition = (fvgMidpoint - rangeLow) / totalRange;

                if (bullish) {
                    // Valid bullish FVG should be in lower portion (near 0 level, 0-0.5)
                    gannBoxValid = normalizedPosition <= 0.5;
                } else {
                    // Valid bearish FVG should be in upper portion (near 1 level, 0.5-1)
                    gannBoxValid = normalizedPosition >= 0.5;
                }

                details.append(String.format("Gann Box %.0f%% %s | ",
                        normalizedPosition * 100,
                        gannBoxValid ? "✅" : "❌"));
            } else {
                details.append("Gann Box N/A | ");
            }
        } else {
            details.append("Gann Box N/A | ");
        }

        iob.setFvgGannBoxValid(gannBoxValid);
        if (gannBoxValid) {
            factorsPassed++;
        }

        // ========== Factor 6: FVG Proximity to BOS Level ==========
        // The FVG (and its parent OB candle) should form close to the structural BOS level.
        // An OB candle far from the broken swing level belongs to a different leg and is not
        // a reliable IOB zone. Threshold: IOB zone midpoint within 2% of the BOS level.
        boolean bosConfirmed = false;
        if (iob.getBosLevel() != null && iob.getBosLevel() > 0) {
            double zoneMid = (iob.getZoneHigh() + iob.getZoneLow()) / 2.0;
            double bosProximityPct = Math.abs(zoneMid - iob.getBosLevel()) / iob.getBosLevel() * 100.0;
            bosConfirmed = bosProximityPct <= 2.0; // OB zone within 2% of broken structural level
        }

        iob.setFvgBosConfirmed(bosConfirmed);
        if (bosConfirmed) {
            factorsPassed++;
            details.append("BOS ✅");
        } else {
            details.append("BOS ❌");
        }

        // ========== Calculate composite score ==========
        // 5 factors evaluated now (Priority is deferred, scored in updateFvgScoreWithPriority)
        double score = (factorsPassed / 5.0) * 100.0;

        iob.setFvgValidationScore(score);
        iob.setFvgValid(score >= 50.0);
        iob.setFvgValidationDetails(String.format("%d/5: %s", factorsPassed, details));

        logger.debug("FVG Validation for {} IOB: Score={}, Valid={}, Details={}",
                bullish ? "BULLISH" : "BEARISH", score, iob.getFvgValid(), iob.getFvgValidationDetails());
    }

    /**
     * Post-pass: Assign FVG priority rankings among IOBs of the same type.
     * Bullish: lowest FVG zone = highest priority (rank 1)
     * Bearish: highest FVG zone = highest priority (rank 1)
     */
    private void assignFvgPriority(List<InternalOrderBlock> iobs) {
        List<InternalOrderBlock> bullishWithFvg = iobs.stream()
                .filter(i -> "BULLISH_IOB".equals(i.getObType()) && Boolean.TRUE.equals(i.getHasFvg()))
                .collect(java.util.stream.Collectors.toList());

        List<InternalOrderBlock> bearishWithFvg = iobs.stream()
                .filter(i -> "BEARISH_IOB".equals(i.getObType()) && Boolean.TRUE.equals(i.getHasFvg()))
                .collect(java.util.stream.Collectors.toList());

        // Bullish: lowest FVG zone = highest priority (rank 1)
        bullishWithFvg.sort(Comparator.comparingDouble(i -> i.getFvgLow() != null ? i.getFvgLow() : Double.MAX_VALUE));
        rankAndScore(bullishWithFvg);

        // Bearish: highest FVG zone = highest priority (rank 1)
        bearishWithFvg.sort((a, b) -> Double.compare(
                b.getFvgHigh() != null ? b.getFvgHigh() : Double.NEGATIVE_INFINITY,
                a.getFvgHigh() != null ? a.getFvgHigh() : Double.NEGATIVE_INFINITY));
        rankAndScore(bearishWithFvg);
    }

    /** Assign sequential priority ranks and update FVG scores for a pre-sorted IOB list. */
    private void rankAndScore(List<InternalOrderBlock> ranked) {
        int total = ranked.size();
        for (int i = 0; i < total; i++) {
            int priority = i + 1;
            InternalOrderBlock iob = ranked.get(i);
            iob.setFvgPriority(priority);
            updateFvgScoreWithPriority(iob, priority, priority <= Math.max(1, total / 2));
        }
    }

    /**
     * Update FVG validation score and details with priority factor result
     */
    private void updateFvgScoreWithPriority(InternalOrderBlock iob, int priority, boolean isPriorityGood) {
        // Recalculate score including priority (now 6 factors)
        int factorsPassed = 0;
        if (Boolean.TRUE.equals(iob.getFvgUnmitigated())) factorsPassed++;
        if (Boolean.TRUE.equals(iob.getFvgCandleReactionValid())) factorsPassed++;
        if (Boolean.TRUE.equals(iob.getFvgSrConfluence())) factorsPassed++;
        if (isPriorityGood) factorsPassed++;
        if (Boolean.TRUE.equals(iob.getFvgGannBoxValid())) factorsPassed++;
        if (Boolean.TRUE.equals(iob.getFvgBosConfirmed())) factorsPassed++;

        double score = (factorsPassed / 6.0) * 100.0;
        iob.setFvgValidationScore(score);
        iob.setFvgValid(score >= 50.0);

        // Rebuild details string with priority info
        StringBuilder details = new StringBuilder()
                .append(fvgFlag(iob.getFvgUnmitigated(), "Unmitigated")).append(" | ")
                .append(fvgFlag(iob.getFvgCandleReactionValid(), "Candle Reaction")).append(" | ")
                .append(fvgFlag(iob.getFvgSrConfluence(), "S/R Confluence")).append(" | ")
                .append("Priority #").append(priority).append(isPriorityGood ? " ✅" : " ❌").append(" | ")
                .append(fvgFlag(iob.getFvgGannBoxValid(), "Gann Box")).append(" | ")
                .append(fvgFlag(iob.getFvgBosConfirmed(), "BOS"));

        iob.setFvgValidationDetails(String.format("%d/6: %s", factorsPassed, details));

        logger.debug("FVG Priority assigned: {} IOB priority #{}, score={}, valid={}",
                iob.getObType(), priority, score, iob.getFvgValid());
    }

    /**
     * Generate a unique signature for an IOB based on its key characteristics.
     * This is used to prevent duplicate IOB detections for the same candle/zone.
     */
    private String generateIOBSignature(InternalOrderBlock iob) {
        // Signature format: {instrumentToken}_{obType}_{obCandleTime}_{zoneLow}_{zoneHigh}
        String candleTimeStr = iob.getObCandleTime() != null
            ? iob.getObCandleTime().format(SIGNATURE_FORMATTER)
            : "unknown";

        return String.format("%d_%s_%s_%.2f_%.2f",
                iob.getInstrumentToken(),
                iob.getObType(),
                candleTimeStr,
                iob.getZoneLow() != null ? iob.getZoneLow() : 0.0,
                iob.getZoneHigh() != null ? iob.getZoneHigh() : 0.0);
    }

    /**
     * Check if IOB is duplicate - uses signature-based and zone-overlap detection
     */
    private boolean isDuplicateIOB(InternalOrderBlock iob) {
        String signature = iob.getIobSignature(); // set by caller before filter

        // Check if exact signature already exists
        if (iobRepository.existsByIobSignature(signature)) {
            logger.debug("Duplicate IOB detected by signature: {}", signature);
            return true;
        }

        // Check by candle time - same candle cannot produce multiple IOBs of same type
        if (iob.getObCandleTime() != null) {
            List<InternalOrderBlock> sameCandleIOBs = iobRepository.findByInstrumentTypeAndCandleTime(
                    iob.getInstrumentToken(), iob.getObType(), iob.getObCandleTime());
            if (!sameCandleIOBs.isEmpty()) {
                logger.debug("Duplicate IOB detected by candle time: {}", iob.getObCandleTime());
                return true;
            }
        }

        // Check for overlapping zones in recent IOBs (last 24 hours)
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        List<InternalOrderBlock> recentIOBs = iobRepository.findAllIOBsSince(iob.getInstrumentToken(), since);

        for (InternalOrderBlock existingIOB : recentIOBs) {
            if (!iob.getObType().equals(existingIOB.getObType())) continue;
            double overlapPercent = calculateZoneOverlapPercent(
                    iob.getZoneLow(), iob.getZoneHigh(),
                    existingIOB.getZoneLow(), existingIOB.getZoneHigh());
            if (overlapPercent > 80) {
                logger.debug("Duplicate IOB detected by zone overlap ({}%): existing={}, new={}",
                        String.format("%.1f", overlapPercent), existingIOB.getId(), signature);
                return true;
            }
        }
        return false;
    }

    /**
     * Calculate the percentage overlap between two IOB zones
     */
    /** Returns what percentage of the smaller zone is covered by the intersection of both zones. */
    private static double calculateZoneOverlapPercent(double low1, double high1, double low2, double high2) {
        double overlapLow = Math.max(low1, low2);
        double overlapHigh = Math.min(high1, high2);
        if (overlapHigh <= overlapLow) return 0.0;
        double overlapSize = overlapHigh - overlapLow;
        double minSize = Math.min(high1 - low1, high2 - low2);
        return (overlapSize / minSize) * 100.0;
    }

    /**
     * Fetch historical candles from Kite API
     */
    private List<HistoricalCandle> fetchHistoricalCandles(Long instrumentToken, String timeframe) {
        try {
            String kiteInterval;
            int lookbackDays;
            if ("5min".equals(timeframe)) {
                kiteInterval = "5minute";
                lookbackDays = 5;
            } else if ("15min".equals(timeframe)) {
                kiteInterval = "15minute";
                lookbackDays = 10;
            } else if ("1hour".equals(timeframe)) {
                kiteInterval = "60minute";
                lookbackDays = 30;
            } else if ("daily".equals(timeframe)) {
                kiteInterval = "day";
                lookbackDays = 180; // ~6 months for daily IOB detection
            } else {
                logger.warn("Unknown timeframe '{}' for token={}, defaulting to 5minute", timeframe, instrumentToken);
                kiteInterval = "5minute";
                lookbackDays = 5;
            }

            LocalDateTime toDate = LocalDateTime.now();
            LocalDateTime fromDate = toDate.minusDays(lookbackDays);

            HistoricalDataRequest request = HistoricalDataRequest.builder()
                    .instrumentToken(String.valueOf(instrumentToken))
                    .fromDate(fromDate)
                    .toDate(toDate)
                    .interval(kiteInterval)
                    .continuous(false)
                    .oi(false)
                    .build();

            HistoricalDataResponse response = instrumentService.getHistoricalData(request);

            if (response != null && response.isSuccess() && response.getCandles() != null) {
                return response.getCandles();
            }
            logger.warn("No candle data returned for token={} timeframe={}: response={}",
                    instrumentToken, timeframe, response != null ? "failed/empty" : "null");
        } catch (Exception e) {
            logger.error("Error fetching historical candles for token={} timeframe={}", instrumentToken, timeframe, e);
        }
        return Collections.emptyList();
    }

    /**
     * Parse timestamp string to LocalDateTime
     */
    private LocalDateTime parseTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            logger.warn("parseTimestamp called with null/blank value — returning null");
            return null;
        }
        try {
            ZonedDateTime zdt = ZonedDateTime.parse(timestamp, TIMESTAMP_FORMATTER);
            return zdt.toLocalDateTime();
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(timestamp.replace("Z", "").replace("T", " ").replace(" ", "T"));
            } catch (Exception e2) {
                logger.warn("Failed to parse timestamp '{}' with both formats — returning null", timestamp);
                return null;
            }
        }
    }

    /**
     * Get instrument name from token
     */
    private String getInstrumentName(Long instrumentToken) {
        if (instrumentToken.equals(NIFTY_INSTRUMENT_TOKEN)) return "NIFTY";
        return "UNKNOWN";
    }

    // ==================== Service Interface Methods ====================

    @Override
    public List<InternalOrderBlock> getFreshIOBs(Long instrumentToken) {
        return iobRepository.findFreshIOBs(instrumentToken);
    }

    @Override
    public List<InternalOrderBlock> getBullishIOBs(Long instrumentToken) {
        return iobRepository.findFreshIOBsByType(instrumentToken, "BULLISH_IOB");
    }

    @Override
    public List<InternalOrderBlock> getBearishIOBs(Long instrumentToken) {
        return iobRepository.findFreshIOBsByType(instrumentToken, "BEARISH_IOB");
    }

    @Override
    public List<InternalOrderBlock> getTodaysIOBs(Long instrumentToken) {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        return iobRepository.findTodaysIOBs(instrumentToken, startOfDay);
    }

    @Override
    public List<InternalOrderBlock> getValidTradableIOBs(Long instrumentToken) {
        return iobRepository.findValidTradableIOBs(instrumentToken);
    }

    /**
     * Computes which sub-zone of the IOB price entered and how deep the retracement is.
     * HIGH_ZONE = first half of zone from the entry side (price just touched the zone).
     * LOW_ZONE  = second half of zone (price retracted deeper, past the midpoint).
     */
    private void computeZoneEntryLevel(InternalOrderBlock iob, Double price) {
        if (iob.getZoneHigh() == null || iob.getZoneLow() == null || price == null) return;
        double high = iob.getZoneHigh();
        double low  = iob.getZoneLow();
        double size = high - low;
        if (size <= 0) return;

        double mid = (high + low) / 2.0;
        boolean bullish = "BULLISH_IOB".equals(iob.getObType());

        // Retracement %: 0 = just touched entry side, 100 = hit the SL/far side
        double retracePct;
        String level;
        if (bullish) {
            // Price enters from above: retracement depth = distance down from zoneHigh
            retracePct = ((high - price) / size) * 100.0;
            level = price >= mid ? "HIGH_ZONE" : "LOW_ZONE";
        } else {
            // Price enters from below: retracement depth = distance up from zoneLow
            retracePct = ((price - low) / size) * 100.0;
            level = price <= mid ? "HIGH_ZONE" : "LOW_ZONE";
        }

        iob.setZoneEntryLevel(level);
        iob.setZoneRetracementPercent(Math.min(100.0, Math.max(0.0, retracePct)));
        logger.debug("IOB {} zone entry: {} ({:.1f}% retracement) at price {}",
                iob.getId(), level, retracePct, price);
    }

    @Override
    public List<InternalOrderBlock> checkMitigation(Long instrumentToken, Double currentPrice) {
        List<InternalOrderBlock> mitigatedIOBs = iobRepository.findIOBsAtPrice(instrumentToken, currentPrice);
        if (mitigatedIOBs.isEmpty()) return mitigatedIOBs;

        LocalDateTime now = LocalDateTime.now();

        // Step 1: mutate all fields including the alert flag — mark before save so a failed
        // Telegram call after a successful save does not cause a duplicate alert on the next tick.
        for (InternalOrderBlock iob : mitigatedIOBs) {
            iob.setStatus("MITIGATED");
            iob.setMitigationTime(now);
            iob.setCurrentPrice(currentPrice);
            iob.setEntryTriggeredTime(now);
            iob.setActualEntryPrice(currentPrice);
            iob.setTradeOutcome("ACTIVE");
            iob.setMitigationAlertSent(true);
            computeZoneEntryLevel(iob, currentPrice);
        }

        // Step 2: persist before sending alerts — if saveAll throws, DB is still FRESH and
        // the next tick will retry cleanly; no alert will have fired yet.
        iobRepository.saveAll(mitigatedIOBs);

        // Step 3: best-effort notification after data is safe in DB.
        for (InternalOrderBlock iob : mitigatedIOBs) {
            sendIOBEntryTelegramAlert(iob, currentPrice);
            logger.info("IOB mitigated: id={} entryTriggeredAt={} price={}", iob.getId(), now, currentPrice);
        }

        return mitigatedIOBs;
    }

    /**
     * Check if any targets have been hit for mitigated IOBs and send alerts.
     * This should be called periodically with the current price.
     */
    @Override
    public void checkTargetHits(Long instrumentToken, Double currentPrice) {
        // findIOBsNeedingTargetMonitoring already filters MITIGATED/TRADED with target3AlertSent=false.
        // Using findTodaysIOBs here would silently drop IOBs detected on a prior day that are still active.
        List<InternalOrderBlock> monitored = iobRepository.findIOBsNeedingTargetMonitoring(instrumentToken);
        if (monitored.isEmpty()) return;

        LocalDateTime now = LocalDateTime.now();
        List<InternalOrderBlock> changed = new ArrayList<>();
        List<Runnable> pendingAlerts = new ArrayList<>();

        for (InternalOrderBlock iob : monitored) {
            boolean dirty = false;
            boolean isBullish = "BULLISH_IOB".equals(iob.getObType());

            // Track max favorable/adverse excursion
            if (iob.getActualEntryPrice() != null) {
                double excursion = isBullish ? currentPrice - iob.getActualEntryPrice()
                                             : iob.getActualEntryPrice() - currentPrice;
                if (excursion > 0) {
                    if (iob.getMaxFavorableExcursion() == null || excursion > iob.getMaxFavorableExcursion()) {
                        iob.setMaxFavorableExcursion(excursion);
                        dirty = true;
                    }
                } else if (excursion < 0) {
                    double adverse = -excursion;
                    if (iob.getMaxAdverseExcursion() == null || adverse > iob.getMaxAdverseExcursion()) {
                        iob.setMaxAdverseExcursion(adverse);
                        dirty = true;
                    }
                }
            }

            // Check Target 1
            if (iob.getTarget1() != null && !Boolean.TRUE.equals(iob.getTarget1AlertSent())) {
                if (isBullish ? currentPrice >= iob.getTarget1() : currentPrice <= iob.getTarget1()) {
                    iob.setTarget1HitTime(now);
                    iob.setTarget1HitPrice(currentPrice);
                    iob.setTarget1AlertSent(true);
                    pendingAlerts.add(() -> sendTargetHitAlert(iob, 1, currentPrice));
                    logger.info("IOB {} Target 1 hit at {} price {}", iob.getId(), now, currentPrice);
                    dirty = true;
                }
            }

            // Check Target 2
            if (iob.getTarget2() != null && !Boolean.TRUE.equals(iob.getTarget2AlertSent())) {
                if (isBullish ? currentPrice >= iob.getTarget2() : currentPrice <= iob.getTarget2()) {
                    iob.setTarget2HitTime(now);
                    iob.setTarget2HitPrice(currentPrice);
                    iob.setTarget2AlertSent(true);
                    pendingAlerts.add(() -> sendTargetHitAlert(iob, 2, currentPrice));
                    logger.info("IOB {} Target 2 hit at {} price {}", iob.getId(), now, currentPrice);
                    dirty = true;
                }
            }

            // Check Target 3
            if (iob.getTarget3() != null && !Boolean.TRUE.equals(iob.getTarget3AlertSent())) {
                if (isBullish ? currentPrice >= iob.getTarget3() : currentPrice <= iob.getTarget3()) {
                    iob.setTarget3HitTime(now);
                    iob.setTarget3HitPrice(currentPrice);
                    iob.setTarget3AlertSent(true);
                    iob.setTradeOutcome("WIN");
                    iob.setStatus("COMPLETED");
                    if (iob.getActualEntryPrice() != null) {
                        iob.setPointsCaptured(isBullish ? currentPrice - iob.getActualEntryPrice()
                                                       : iob.getActualEntryPrice() - currentPrice);
                    }
                    pendingAlerts.add(() -> sendTargetHitAlert(iob, 3, currentPrice));
                    logger.info("IOB {} Target 3 hit at {} price {} - TRADE WIN", iob.getId(), now, currentPrice);
                    dirty = true;
                }
            }

            // Check Stop Loss — status may have just been set to COMPLETED above by T3
            if (iob.getStopLoss() != null && !"STOPPED".equals(iob.getStatus()) && !"COMPLETED".equals(iob.getStatus())) {
                if (isBullish ? currentPrice <= iob.getStopLoss() : currentPrice >= iob.getStopLoss()) {
                    // Use the SL level as the recorded exit price, not the current polling price.
                    // If price gapped through the SL (e.g. crash overnight), currentPrice can be
                    // thousands of points past the SL — using it would wildly overstate the loss.
                    double slExitPrice = iob.getStopLoss();
                    iob.setStopLossHitTime(now);
                    iob.setStopLossHitPrice(slExitPrice);
                    iob.setTradeOutcome("LOSS");
                    if (iob.getActualEntryPrice() != null) {
                        iob.setPointsCaptured(isBullish ? slExitPrice - iob.getActualEntryPrice()
                                                       : iob.getActualEntryPrice() - slExitPrice);
                    }
                    iob.setStatus("STOPPED");
                    pendingAlerts.add(() -> sendStopLossHitAlert(iob, slExitPrice));
                    logger.info("IOB {} Stop Loss hit at {} price {} - TRADE LOSS", iob.getId(), now, currentPrice);
                    dirty = true;
                }
            }

            if (dirty) changed.add(iob);
        }

        // Persist all mutations before sending alerts — if saveAll fails, no alerts fire and
        // the alert flags are still unset in DB so the next tick retries cleanly.
        if (!changed.isEmpty()) {
            iobRepository.saveAll(changed);
        }

        // Best-effort notifications after data is safe in DB.
        pendingAlerts.forEach(Runnable::run);
    }

    /**
     * Send target hit alert via Telegram
     */
    private void sendTargetHitAlert(InternalOrderBlock iob, int targetNumber, Double hitPrice) {
        if (telegramNotificationService == null || !telegramNotificationService.isConfigured()) {
            return;
        }

        // Check if target hit alerts are enabled
        if (!telegramNotificationService.isAlertTypeEnabled("PREDICTION", "TARGET_HIT")) {
            return;
        }

        try {
            String instrumentName = iob.getInstrumentName();
            String direction = iob.getObType().contains("BULLISH") ? "BULLISH" : "BEARISH";
            String emoji = "🎯";

            Double targetPrice = switch (targetNumber) {
                case 1 -> iob.getTarget1();
                case 2 -> iob.getTarget2();
                case 3 -> iob.getTarget3();
                default -> 0.0;
            };

            // Calculate profit from actual entry
            Double actualEntry = iob.getActualEntryPrice() != null ? iob.getActualEntryPrice() : iob.getEntryPrice();
            double profitPoints = 0;
            if (actualEntry != null) {
                profitPoints = direction.equals("BULLISH") ? hitPrice - actualEntry : actualEntry - hitPrice;
            }

            String title = String.format("%s TARGET %d HIT - %s", emoji, targetNumber, instrumentName);
            String message = String.format("%s IOB Target %d reached!\n" +
                    "Target: ₹%.2f | Hit at: ₹%.2f\n" +
                    "Profit: +%.2f pts",
                    direction, targetNumber, targetPrice, hitPrice, profitPoints);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("Instrument", instrumentName);
            data.put("IOB Type", iob.getObType());
            data.put("Target", "T" + targetNumber);
            data.put("Target Price", String.format("₹%.2f", targetPrice));
            data.put("Hit Price", String.format("₹%.2f", hitPrice));
            data.put("Actual Entry", String.format("₹%.2f", actualEntry != null ? actualEntry : 0));
            data.put("Profit Points", String.format("+%.2f", profitPoints));
            data.put("Zone", String.format("₹%.2f - ₹%.2f", iob.getZoneLow(), iob.getZoneHigh()));

            if (iob.getEntryTriggeredTime() != null) {
                data.put("Entry Time", iob.getEntryTriggeredTime().format(
                        TIME_FORMATTER));
            }

            // Add hit time
            data.put("Hit Time", LocalDateTime.now().format(
                    TIME_FORMATTER));

            if (iob.getDetectionTimestamp() != null) {
                data.put("Signal Detected", iob.getDetectionTimestamp().format(
                        DATETIME_FORMATTER));
            }

            telegramNotificationService.sendTradeAlertAsync(title, message, data);
            logger.info("Sent Target {} hit alert for IOB {}", targetNumber, iob.getId());

        } catch (Exception e) {
            logger.warn("Failed to send target hit alert for IOB {}", iob.getId(), e);
        }
    }

    /**
     * Send stop loss hit alert via Telegram
     */
    private void sendStopLossHitAlert(InternalOrderBlock iob, Double hitPrice) {
        if (telegramNotificationService == null || !telegramNotificationService.isConfigured()) {
            return;
        }

        try {
            String instrumentName = iob.getInstrumentName();
            String direction = iob.getObType().contains("BULLISH") ? "BULLISH" : "BEARISH";
            String emoji = "🛑";

            // Calculate loss from actual entry
            Double actualEntry = iob.getActualEntryPrice() != null ? iob.getActualEntryPrice() : iob.getEntryPrice();
            double lossPoints = 0;
            if (actualEntry != null) {
                lossPoints = direction.equals("BULLISH") ? actualEntry - hitPrice : hitPrice - actualEntry;
            }

            String title = String.format("%s STOP LOSS HIT - %s", emoji, instrumentName);
            String message = String.format("%s IOB Stop Loss triggered!\n" +
                    "SL: ₹%.2f | Hit at: ₹%.2f\n" +
                    "Loss: -%.2f pts",
                    direction, iob.getStopLoss(), hitPrice, lossPoints);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("Instrument", instrumentName);
            data.put("IOB Type", iob.getObType());
            data.put("Stop Loss", String.format("₹%.2f", iob.getStopLoss()));
            data.put("Hit Price", String.format("₹%.2f", hitPrice));
            data.put("Actual Entry", String.format("₹%.2f", actualEntry != null ? actualEntry : 0));
            data.put("Loss Points", String.format("-%.2f", lossPoints));
            data.put("Zone", String.format("₹%.2f - ₹%.2f", iob.getZoneLow(), iob.getZoneHigh()));

            if (iob.getEntryTriggeredTime() != null) {
                data.put("Entry Time", iob.getEntryTriggeredTime().format(
                        TIME_FORMATTER));
            }

            // Add hit time
            data.put("SL Hit Time", LocalDateTime.now().format(
                    TIME_FORMATTER));

            if (iob.getMaxFavorableExcursion() != null) {
                data.put("Max Profit Reached", String.format("+%.2f pts", iob.getMaxFavorableExcursion()));
            }

            telegramNotificationService.sendTradeAlertAsync(title, message, data);
            logger.info("Sent Stop Loss hit alert for IOB {}", iob.getId());

        } catch (Exception e) {
            logger.warn("Failed to send stop loss hit alert for IOB {}", iob.getId(), e);
        }
    }

    @Override
    public void markAsMitigated(Long iobId) {
        iobRepository.findById(iobId).ifPresent(iob -> {
            // Only send alert if IOB was FRESH and alert not already sent
            boolean shouldSendAlert = "FRESH".equals(iob.getStatus()) &&
                                      !Boolean.TRUE.equals(iob.getMitigationAlertSent());

            iob.setStatus("MITIGATED");
            iob.setMitigationTime(LocalDateTime.now());
            if (shouldSendAlert) {
                iob.setMitigationAlertSent(true); // set before save so a failed Telegram call doesn't cause duplicate
            }
            iobRepository.save(iob);

            // Send Telegram notification after save — DB is consistent regardless of Telegram outcome
            if (shouldSendAlert) {
                sendIOBMitigationTelegramAlert(iob, iob.getCurrentPrice(), true);
            }
        });
    }

    @Override
    public int mitigateAllFresh(Long instrumentToken) {
        List<InternalOrderBlock> freshIOBs = iobRepository.findFreshIOBs(instrumentToken);

        if (freshIOBs.isEmpty()) {
            logger.info("No fresh IOBs found for instrument token: {}", instrumentToken);
            return 0;
        }

        LocalDateTime now = LocalDateTime.now();
        for (InternalOrderBlock iob : freshIOBs) {
            iob.setStatus("MITIGATED");
            iob.setMitigationTime(now);
        }

        iobRepository.saveAll(freshIOBs);
        logger.info("Mitigated {} fresh IOBs for instrument token: {}", freshIOBs.size(), instrumentToken);

        return freshIOBs.size();
    }

    @Override
    public void markAsTraded(Long iobId, String tradeId) {
        markAsTraded(iobId, tradeId, null, null, null);
    }

    @Override
    public void markAsTraded(Long iobId, String tradeId, Double premiumT1, Double premiumT2, Double premiumT3) {
        iobRepository.findById(iobId).ifPresent(iob -> {
            iob.setTradeTaken(true);
            iob.setTradeId(tradeId);
            iob.setStatus("TRADED");
            iob.setPremiumT1(premiumT1);
            iob.setPremiumT2(premiumT2);
            iob.setPremiumT3(premiumT3);
            iobRepository.save(iob);
        });
    }

    @Override
    public int markAsCompleted(List<Long> iobIds) {
        if (iobIds == null || iobIds.isEmpty()) {
            return 0;
        }

        List<InternalOrderBlock> iobs = (List<InternalOrderBlock>) iobRepository.findAllById(iobIds);

        for (InternalOrderBlock iob : iobs) {
            iob.setStatus("COMPLETED");
            iob.setTarget1AlertSent(true);
            iob.setTarget2AlertSent(true);
            iob.setTarget3AlertSent(true);
        }

        iobRepository.saveAll(iobs);
        logger.info("Marked {} IOBs as completed", iobs.size());
        return iobs.size();
    }

    @Override
    public Map<String, Object> getDashboardData(List<Long> instrumentTokens) {
        Map<String, Object> dashboard = new HashMap<>();

        List<Map<String, Object>> allIOBs = new ArrayList<>();

        for (Long token : instrumentTokens) {
            // First scan for new IOBs
            scanForIOBs(token);

            // Then get fresh IOBs
            List<InternalOrderBlock> freshIOBs = getFreshIOBs(token);
            for (InternalOrderBlock iob : freshIOBs) {
                allIOBs.add(convertToMap(iob));
            }
        }

        dashboard.put("iobs", allIOBs);
        dashboard.put("totalIOBs", allIOBs.size());
        dashboard.put("bullishCount", allIOBs.stream()
                .filter(i -> "BULLISH_IOB".equals(i.get("obType"))).count());
        dashboard.put("bearishCount", allIOBs.stream()
                .filter(i -> "BEARISH_IOB".equals(i.get("obType"))).count());
        dashboard.put("timestamp", LocalDateTime.now());

        return dashboard;
    }

    @Override
    public Map<String, Object> getDetailedAnalysis(Long instrumentToken) {
        Map<String, Object> result = new HashMap<>();

        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        // Look back 7 days for completed IOBs so recently marked ones are visible
        LocalDateTime sevenDaysAgo = startOfDay.minusDays(7);

        // Scan for IOBs
        List<InternalOrderBlock> detectedIOBs = scanForIOBs(instrumentToken);

        // Get active IOBs - use findAllActiveIOBs (excludes MITIGATED, STOPPED, COMPLETED, EXPIRED)
        List<InternalOrderBlock> activeIOBs = iobRepository.findAllActiveIOBs(instrumentToken);

        // Get mitigated IOBs - those that have been touched but not completed
        List<InternalOrderBlock> mitigatedIOBs = iobRepository.findMitigatedIOBs(instrumentToken);

        // Get completed IOBs from the last 7 days (target 3 hit or stop loss hit or manually marked)
        List<InternalOrderBlock> completedIOBs = iobRepository.findCompletedIOBsSince(instrumentToken, sevenDaysAgo);

        result.put("instrumentToken", instrumentToken);
        result.put("instrumentName", getInstrumentName(instrumentToken));
        result.put("detectedIOBs", detectedIOBs.stream().map(this::convertToMap).toList());
        result.put("freshBullishIOBs", getBullishIOBs(instrumentToken).stream().map(this::convertToMap).toList());
        result.put("freshBearishIOBs", getBearishIOBs(instrumentToken).stream().map(this::convertToMap).toList());
        result.put("tradableIOBs", getValidTradableIOBs(instrumentToken).stream().map(this::convertToMap).toList());

        // Include active, mitigated, and completed IOBs separately
        result.put("activeIOBs", activeIOBs.stream().map(this::convertToMap).toList());
        result.put("mitigatedIOBs", mitigatedIOBs.stream().map(this::convertToMap).toList());
        result.put("completedIOBs", completedIOBs.stream().map(this::convertToMap).toList());

        result.put("activeCount", activeIOBs.size());
        result.put("mitigatedCount", mitigatedIOBs.size());
        result.put("completedCount", completedIOBs.size());
        result.put("timestamp", LocalDateTime.now());

        return result;
    }

    @Override
    public Map<String, Object> analyzeAllIndices() {
        Map<String, Object> result = new HashMap<>();

        List<Long> tokens = List.of(NIFTY_INSTRUMENT_TOKEN);

        Map<String, Object> analyses = new HashMap<>();
        for (Long token : tokens) {
            analyses.put(getInstrumentName(token), getDetailedAnalysis(token));
        }

        result.put("analyses", analyses);
        result.put("timestamp", LocalDateTime.now());

        return result;
    }

    @Override
    public Map<String, Object> generateTradeSetup(Long iobId) {
        Map<String, Object> setup = new HashMap<>();

        iobRepository.findById(iobId).ifPresent(iob -> {
            setup.put("iobId", iob.getId());
            setup.put("instrumentName", iob.getInstrumentName());
            setup.put("direction", iob.getTradeDirection());
            setup.put("entryZone", Map.of("high", iob.getZoneHigh(), "low", iob.getZoneLow()));
            setup.put("entryPrice", iob.getEntryPrice());
            setup.put("stopLoss", iob.getStopLoss());
            setup.put("target1", iob.getTarget1());
            setup.put("target2", iob.getTarget2());
            setup.put("target3", iob.getTarget3());
            setup.put("riskRewardRatio", iob.getRiskRewardRatio());
            setup.put("confidence", iob.getSignalConfidence());
            setup.put("hasFVG", iob.getHasFvg());
            setup.put("validationNotes", iob.getValidationNotes());
        });

        return setup;
    }

    @Override
    public Map<String, Object> executeTrade(Long iobId) {
        Map<String, Object> result = new HashMap<>();

        Optional<InternalOrderBlock> iobOpt = iobRepository.findById(iobId);
        if (iobOpt.isEmpty()) {
            result.put("success", false);
            result.put("message", "IOB not found");
            return result;
        }

        InternalOrderBlock iob = iobOpt.get();

        if (Boolean.TRUE.equals(iob.getTradeTaken())) {
            result.put("success", false);
            result.put("message", "Trade already taken for this IOB");
            return result;
        }

        try {
            String tradeId = "IOB_" + iob.getId() + "_" + System.currentTimeMillis();

            // Collect targets safely — List.of rejects nulls; filter first so a partially-set
            // IOB doesn't throw after markAsTraded has already committed the mutation.
            List<Double> targets = java.util.stream.Stream.of(iob.getTarget1(), iob.getTarget2(), iob.getTarget3())
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toList());

            // markAsTraded is the point of no return — call it only after all data prep succeeds.
            markAsTraded(iob.getId(), tradeId);

            result.put("success", true);
            result.put("tradeId", tradeId);
            result.put("direction", iob.getTradeDirection());
            result.put("entryPrice", iob.getEntryPrice());
            result.put("stopLoss", iob.getStopLoss());
            result.put("targets", targets);
            result.put("message", "Trade executed successfully based on " + iob.getObType());

            logger.info("Trade executed for IOB {}: {} at {}", iobId, iob.getTradeDirection(), iob.getEntryPrice());

        } catch (Exception e) {
            logger.error("Error executing trade for IOB {}", iobId, e);
            result.put("success", false);
            result.put("message", "Failed to execute trade");
        }

        return result;
    }

    @Override
    public void expireOldIOBs() {
        LocalDateTime cutoffTime = LocalDateTime.now().minusDays(2);
        iobRepository.expireOldIOBs(cutoffTime);
        logger.info("Expired old IOBs before {}", cutoffTime);
    }

    @Override
    public Map<String, Object> getStatistics(Long instrumentToken) {
        Map<String, Object> stats = new HashMap<>();

        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        List<InternalOrderBlock> todaysIOBs = iobRepository.findTodaysIOBs(instrumentToken, startOfDay);

        stats.put("totalToday", todaysIOBs.size());
        stats.put("bullishToday", todaysIOBs.stream().filter(i -> "BULLISH_IOB".equals(i.getObType())).count());
        stats.put("bearishToday", todaysIOBs.stream().filter(i -> "BEARISH_IOB".equals(i.getObType())).count());
        stats.put("mitigatedToday", todaysIOBs.stream().filter(i -> "MITIGATED".equals(i.getStatus())).count());
        stats.put("tradedToday", todaysIOBs.stream().filter(i -> Boolean.TRUE.equals(i.getTradeTaken())).count());
        stats.put("freshCount", iobRepository.countFreshIOBsByType(instrumentToken, "BULLISH_IOB") +
                                iobRepository.countFreshIOBsByType(instrumentToken, "BEARISH_IOB"));

        return stats;
    }

    /**
     * Convert IOB entity to map for API response
     */
    private Map<String, Object> convertToMap(InternalOrderBlock iob) {
        Map<String, Object> map = new HashMap<>();

        map.put("id", iob.getId());
        map.put("instrumentToken", iob.getInstrumentToken());
        map.put("instrumentName", iob.getInstrumentName());
        map.put("timeframe", iob.getTimeframe());
        map.put("detectionTimestamp", iob.getDetectionTimestamp());
        map.put("obCandleTime", iob.getObCandleTime());
        map.put("obType", iob.getObType());
        map.put("obHigh", iob.getObHigh());
        map.put("obLow", iob.getObLow());
        map.put("obOpen", iob.getObOpen());
        map.put("obClose", iob.getObClose());
        map.put("zoneHigh", iob.getZoneHigh());
        map.put("zoneLow", iob.getZoneLow());
        map.put("zoneMidpoint", iob.getZoneMidpoint());
        map.put("currentPrice", iob.getCurrentPrice());
        map.put("distanceToZone", iob.getDistanceToZone());
        map.put("distancePercent", iob.getDistancePercent());
        map.put("bosLevel", iob.getBosLevel());
        map.put("bosType", iob.getBosType());
        map.put("hasFvg", iob.getHasFvg());
        map.put("fvgHigh", iob.getFvgHigh());
        map.put("fvgLow", iob.getFvgLow());
        map.put("fvgValid", iob.getFvgValid());
        map.put("fvgValidationScore", iob.getFvgValidationScore());
        map.put("fvgValidationDetails", iob.getFvgValidationDetails());
        map.put("fvgPriority", iob.getFvgPriority());
        map.put("fvgUnmitigated", iob.getFvgUnmitigated());
        map.put("fvgCandleReactionValid", iob.getFvgCandleReactionValid());
        map.put("fvgSrConfluence", iob.getFvgSrConfluence());
        map.put("fvgGannBoxValid", iob.getFvgGannBoxValid());
        map.put("fvgBosConfirmed", iob.getFvgBosConfirmed());
        map.put("tradeDirection", iob.getTradeDirection());
        map.put("entryPrice", iob.getEntryPrice());
        map.put("stopLoss", iob.getStopLoss());
        map.put("target1", iob.getTarget1());
        map.put("target2", iob.getTarget2());
        map.put("target3", iob.getTarget3());
        map.put("riskRewardRatio", iob.getRiskRewardRatio());
        map.put("status", iob.getStatus());
        map.put("isValid", iob.getIsValid());
        map.put("validationNotes", iob.getValidationNotes());
        map.put("signalConfidence", iob.getSignalConfidence());
        map.put("tradeTaken", iob.getTradeTaken());
        map.put("tradeId", iob.getTradeId());
        map.put("mitigationTime", iob.getMitigationTime());

        // Alert tracking flags
        map.put("detectionAlertSent", iob.getDetectionAlertSent());
        map.put("mitigationAlertSent", iob.getMitigationAlertSent());
        map.put("target1AlertSent", iob.getTarget1AlertSent());
        map.put("target2AlertSent", iob.getTarget2AlertSent());
        map.put("target3AlertSent", iob.getTarget3AlertSent());
        map.put("iobSignature", iob.getIobSignature());

        // Trade timeline tracking
        map.put("entryTriggeredTime", iob.getEntryTriggeredTime());
        map.put("actualEntryPrice", iob.getActualEntryPrice());
        map.put("stopLossHitTime", iob.getStopLossHitTime());
        map.put("stopLossHitPrice", iob.getStopLossHitPrice());
        map.put("target1HitTime", iob.getTarget1HitTime());
        map.put("target1HitPrice", iob.getTarget1HitPrice());
        map.put("target2HitTime", iob.getTarget2HitTime());
        map.put("target2HitPrice", iob.getTarget2HitPrice());
        map.put("target3HitTime", iob.getTarget3HitTime());
        map.put("target3HitPrice", iob.getTarget3HitPrice());
        map.put("maxFavorableExcursion", iob.getMaxFavorableExcursion());
        map.put("maxAdverseExcursion", iob.getMaxAdverseExcursion());
        map.put("tradeOutcome", iob.getTradeOutcome());
        map.put("pointsCaptured", iob.getPointsCaptured());

        return map;
    }

    /**
     * Send Telegram alert for IOB detection (direct, closed-candle detection).
     * Delegates to the shared sendIOBTelegramAlertWithType using "DETECTED" label.
     * Only sends alerts for NIFTY with confidence > threshold from settings.
     */
    private void sendIOBTelegramAlert(InternalOrderBlock iob) {
        sendIOBTelegramAlertWithType(iob, "DETECTED");
    }

    /**
     * Formats the timeframe with from-to time range.
     * Example: "15min" with candle time 14:15:00 -> "15min (14:15:00 - 14:30:00)"
     */
    private String formatTimeframeWithRange(String timeframe, LocalDateTime candleTime) {
        if (timeframe == null) {
            return "Unknown";
        }

        if (candleTime == null) {
            return timeframe;
        }

        try {
            String tf = timeframe.toLowerCase();

            // Daily candles: show the candle date — both ends would be 00:00:00 with time format
            if (tf.equals("daily") || tf.equals("day") || tf.equals("1d")) {
                return String.format("%s (%s)", timeframe, candleTime.format(DATETIME_FORMATTER).substring(0, 10));
            }

            String fromTime = candleTime.format(TIME_FORMATTER);
            LocalDateTime toDateTime;

            // Parse timeframe and calculate end time
            if (tf.contains("min")) {
                int minutes = Integer.parseInt(tf.replace("min", "").trim());
                toDateTime = candleTime.plusMinutes(minutes);
            } else if (tf.contains("hour")) {
                int hours = Integer.parseInt(tf.replace("hour", "").trim());
                toDateTime = candleTime.plusHours(hours);
            } else if (tf.contains("d")) {
                int days = Integer.parseInt(tf.replace("d", "").trim());
                toDateTime = candleTime.plusDays(days);
            } else {
                return timeframe;
            }

            return String.format("%s (%s - %s)", timeframe, fromTime, toDateTime.format(TIME_FORMATTER));

        } catch (Exception e) {
            logger.debug("Error formatting timeframe range for '{}'", timeframe, e);
            return timeframe;
        }
    }

    /**
     * Sends a Telegram notification when an IOB is mitigated.
     * @param iob The mitigated IOB
     * @param mitigationPrice The price at which mitigation occurred
     * @param isManual Whether this was a manual mitigation
     */
    private void sendIOBMitigationTelegramAlert(InternalOrderBlock iob, Double mitigationPrice, boolean isManual) {
        // Check if Telegram service is available and configured
        if (telegramNotificationService == null || !telegramNotificationService.isConfigured()) {
            return;
        }

        // Check if IOB mitigation alerts are enabled in settings
        if (!telegramNotificationService.isAlertTypeEnabled("TRADE", "IOB_MITIGATION")) {
            logger.debug("IOB Mitigation Telegram alerts are disabled in settings");
            return;
        }

        // Only send alerts for NIFTY
        String instrumentName = iob.getInstrumentName();
        boolean isNifty = instrumentName != null && instrumentName.contains("NIFTY");

        if (!isNifty) {
            logger.debug("Skipping Telegram mitigation alert for non-NIFTY instrument: {}", instrumentName);
            return;
        }

        try {
            // Determine direction and emoji
            String direction = iob.getObType().contains("BULLISH") ? "BULLISH" : "BEARISH";
            String directionEmoji = direction.equals("BULLISH") ? "🟢" : "🔴";
            String mitigationType = isManual ? "Manual" : "Auto";
            String actionEmoji = "⚡";

            // Build alert title
            String title = String.format("%s IOB MITIGATED %s - %s", actionEmoji, directionEmoji, instrumentName);

            // Build message
            String message = String.format("%s %s IOB has been %s mitigated on %s\n" +
                "Zone: ₹%.2f - ₹%.2f | Mitigation Price: ₹%.2f",
                directionEmoji, direction, mitigationType.toLowerCase(), instrumentName,
                iob.getZoneLow(), iob.getZoneHigh(),
                mitigationPrice != null ? mitigationPrice : 0.0);

            // Build data map — LinkedHashMap preserves insertion order in the Telegram message
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("Instrument", instrumentName);
            data.put("IOB Type", iob.getObType());
            data.put("Direction", direction);
            data.put("Zone", String.format("₹%.2f - ₹%.2f", iob.getZoneLow(), iob.getZoneHigh()));
            data.put("Zone Midpoint", String.format("₹%.2f", iob.getZoneMidpoint()));

            if (mitigationPrice != null) {
                data.put("Mitigation Price", String.format("₹%.2f", mitigationPrice));
            }

            data.put("Mitigation Type", mitigationType);

            if (iob.getMitigationTime() != null) {
                data.put("Mitigation Time", iob.getMitigationTime().format(
                    DATETIME_FORMATTER));
            }

            // Original signal details
            if (iob.getSignalConfidence() != null) {
                data.put("Original Confidence", String.format("%.1f%%", iob.getSignalConfidence()));
            }

            // Trade setup details (for reference)
            if (iob.getEntryPrice() != null) {
                data.put("Entry Price (Was)", String.format("₹%.2f", iob.getEntryPrice()));
            }
            if (iob.getStopLoss() != null) {
                data.put("Stop-Loss (Was)", String.format("₹%.2f", iob.getStopLoss()));
            }
            if (iob.getTarget1() != null) {
                data.put("Target 1 (Was)", String.format("₹%.2f", iob.getTarget1()));
            }
            if (iob.getTarget2() != null) {
                data.put("Target 2 (Was)", String.format("₹%.2f", iob.getTarget2()));
            }
            if (iob.getTarget3() != null) {
                data.put("Target 3 (Was)", String.format("₹%.2f", iob.getTarget3()));
            }

            // Add timeframe with from-to time range
            String timeframeDisplay = formatTimeframeWithRange(iob.getTimeframe(), iob.getObCandleTime());
            data.put("Timeframe", timeframeDisplay);

            // Add original detection timestamp
            if (iob.getDetectionTimestamp() != null) {
                data.put("Original Detection", iob.getDetectionTimestamp().format(
                    DATETIME_FORMATTER));
            }

            // Send the alert asynchronously
            telegramNotificationService.sendTradeAlertAsync(title, message, data);

            logger.info("Telegram IOB mitigation alert sent for {} {} ({})",
                instrumentName, direction, mitigationType);

        } catch (Exception e) {
            logger.warn("Failed to send IOB mitigation Telegram alert for IOB {}", iob.getId(), e);
        }
    }

    /**
     * Sends a Telegram notification when an IOB entry is triggered (price enters the zone).
     * @param iob The IOB that triggered entry
     * @param entryPrice The price at which entry was triggered
     */
    private void sendIOBEntryTelegramAlert(InternalOrderBlock iob, Double entryPrice) {
        // Check if Telegram service is available and configured
        if (telegramNotificationService == null || !telegramNotificationService.isConfigured()) {
            return;
        }

        // Check if IOB entry alerts are enabled in settings
        if (!telegramNotificationService.isAlertTypeEnabled("TRADE", "IOB_ENTRY")) {
            logger.debug("IOB Entry Telegram alerts are disabled in settings");
            return;
        }

        // Only send alerts for NIFTY
        String instrumentName = iob.getInstrumentName();
        boolean isNifty = instrumentName != null && instrumentName.contains("NIFTY");

        if (!isNifty) {
            logger.debug("Skipping Telegram entry alert for non-NIFTY instrument: {}", instrumentName);
            return;
        }

        try {
            // Determine direction and emoji
            String direction = iob.getObType().contains("BULLISH") ? "BULLISH" : "BEARISH";
            String directionEmoji = direction.equals("BULLISH") ? "🟢" : "🔴";
            String tradeAction = direction.equals("BULLISH") ? "BUY/LONG" : "SELL/SHORT";
            String actionEmoji = "🚀";
            double price = entryPrice != null ? entryPrice : 0.0;

            // Build alert title
            String title = String.format("%s IOB ENTRY TRIGGERED %s - %s", actionEmoji, directionEmoji, instrumentName);

            // Build message
            String message = String.format("%s %s IOB Entry triggered on %s!\n" +
                "%s signal activated at ₹%.2f",
                directionEmoji, direction, instrumentName, tradeAction, price);

            // Build data map
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("Instrument", instrumentName);
            data.put("IOB Type", iob.getObType());
            data.put("Trade Action", tradeAction);
            data.put("Entry Price", String.format("₹%.2f", price));
            data.put("Zone", String.format("₹%.2f - ₹%.2f", iob.getZoneLow(), iob.getZoneHigh()));
            data.put("Zone Midpoint", String.format("₹%.2f", iob.getZoneMidpoint()));
            if (iob.getZoneEntryLevel() != null) {
                String retracePct = iob.getZoneRetracementPercent() != null
                        ? String.format(" (%.0f%% retracement)", iob.getZoneRetracementPercent()) : "";
                data.put("Zone Entry", iob.getZoneEntryLevel() + retracePct);
            }

            if (iob.getEntryTriggeredTime() != null) {
                data.put("Entry Time", iob.getEntryTriggeredTime().format(DATETIME_FORMATTER));
            }

            // Stop Loss and Targets
            if (iob.getStopLoss() != null) {
                data.put("Stop Loss", String.format("₹%.2f", iob.getStopLoss()));
                data.put("Risk Points", String.format("%.2f", Math.abs(price - iob.getStopLoss())));
            }
            if (iob.getTarget1() != null) {
                data.put("Target 1", String.format("₹%.2f", iob.getTarget1()));
            }
            if (iob.getTarget2() != null) {
                data.put("Target 2", String.format("₹%.2f", iob.getTarget2()));
            }
            if (iob.getTarget3() != null) {
                data.put("Target 3", String.format("₹%.2f", iob.getTarget3()));
            }

            if (iob.getRiskRewardRatio() != null) {
                data.put("Risk:Reward", String.format("1:%.1f", iob.getRiskRewardRatio()));
            }

            // Confidence score
            Double confidence = iob.getEnhancedConfidence() != null ? iob.getEnhancedConfidence() : iob.getSignalConfidence();
            if (confidence != null) {
                data.put("Confidence", String.format("%.1f%%", confidence));
            }

            // FVG validation details
            if (Boolean.TRUE.equals(iob.getHasFvg())) {
                double fvgScore = iob.getFvgValidationScore() != null ? iob.getFvgValidationScore() : 0.0;
                data.put("FVG", String.format("%s (Score: %.0f%%)",
                        Boolean.TRUE.equals(iob.getFvgValid()) ? "Valid ✅" : "Invalid ❌", fvgScore));
                if (iob.getFvgPriority() != null && iob.getFvgPriority() > 0) {
                    data.put("FVG Priority", "#" + iob.getFvgPriority());
                }
            }

            // Add timeframe with from-to time range
            String timeframeDisplay = formatTimeframeWithRange(iob.getTimeframe(), iob.getObCandleTime());
            data.put("Timeframe", timeframeDisplay);

            // Add original detection timestamp
            if (iob.getDetectionTimestamp() != null) {
                data.put("Signal Detected", iob.getDetectionTimestamp().format(
                    DATETIME_FORMATTER));
            }

            // Send the alert asynchronously
            telegramNotificationService.sendTradeAlertAsync(title, message, data);

            logger.info("Telegram IOB entry alert sent for {} {} at {}",
                instrumentName, direction, entryPrice);

        } catch (Exception e) {
            logger.warn("Failed to send IOB entry Telegram alert for IOB {}", iob.getId(), e);
        }
    }

    // ==================== Multi-Timeframe Analysis ====================

    // Supported timeframes in order of significance (higher = more significant)
    private static final List<String> TIMEFRAME_HIERARCHY = Arrays.asList("5min", "15min", "1hour", "daily");
    private static final Map<String, Double> TIMEFRAME_WEIGHTS = Map.of(
            "5min", 1.0,
            "15min", 1.5,
            "1hour", 2.0,
            "daily", 3.0
    );


    @Override
    public Map<String, List<InternalOrderBlock>> scanMultipleTimeframes(Long instrumentToken, List<String> timeframes) {
        Map<String, List<InternalOrderBlock>> result = new LinkedHashMap<>();

        for (String timeframe : timeframes) {
            try {
                List<InternalOrderBlock> iobs = scanForIOBs(instrumentToken, timeframe);
                result.put(timeframe, iobs);
                logger.info("Scanned {} IOBs for {} on {}", iobs.size(), instrumentToken, timeframe);
            } catch (Exception e) {
                logger.error("Error scanning {} for IOBs", timeframe, e);
                result.put(timeframe, new ArrayList<>());
            }
        }

        return result;
    }

    @Override
    public Map<String, Object> getMTFAnalysis(Long instrumentToken) {
        Map<String, Object> analysis = new HashMap<>();

        // Scan all timeframes
        Map<String, List<InternalOrderBlock>> mtfIOBs = scanMultipleTimeframes(instrumentToken, TIMEFRAME_HIERARCHY);
        analysis.put("iobsByTimeframe", mtfIOBs);

        // Determine higher timeframe bias
        String htfBias = determineHTFBias(mtfIOBs);
        analysis.put("htfBias", htfBias);

        // Get aligned IOBs (lower timeframe IOBs that match HTF direction)
        List<InternalOrderBlock> alignedIOBs = new ArrayList<>();
        List<InternalOrderBlock> ltfIOBs = mtfIOBs.getOrDefault("5min", new ArrayList<>());

        for (InternalOrderBlock iob : ltfIOBs) {
            String direction = "BULLISH_IOB".equals(iob.getObType()) ? "BULLISH" : "BEARISH";
            if (direction.equals(htfBias) || "NEUTRAL".equals(htfBias)) {
                // Calculate MTF confluence score
                Double confluenceScore = calculateMTFConfluenceScore(iob, instrumentToken);
                double base = iob.getSignalConfidence() != null ? iob.getSignalConfidence() : 50.0;
                iob.setSignalConfidence(Math.min(100, base + confluenceScore));
                alignedIOBs.add(iob);
            }
        }

        analysis.put("alignedIOBs", alignedIOBs.stream().map(this::convertToMap).toList());
        analysis.put("totalAligned", alignedIOBs.size());

        // Summary counts by timeframe
        Map<String, Map<String, Integer>> summaryByTimeframe = new HashMap<>();
        for (Map.Entry<String, List<InternalOrderBlock>> entry : mtfIOBs.entrySet()) {
            Map<String, Integer> counts = new HashMap<>();
            counts.put("bullish", (int) entry.getValue().stream().filter(i -> "BULLISH_IOB".equals(i.getObType())).count());
            counts.put("bearish", (int) entry.getValue().stream().filter(i -> "BEARISH_IOB".equals(i.getObType())).count());
            counts.put("total", entry.getValue().size());
            summaryByTimeframe.put(entry.getKey(), counts);
        }
        analysis.put("summaryByTimeframe", summaryByTimeframe);

        analysis.put("instrumentToken", instrumentToken);
        analysis.put("instrumentName", getInstrumentName(instrumentToken));
        analysis.put("timestamp", LocalDateTime.now());

        return analysis;
    }

    @Override
    public Double calculateMTFConfluenceScore(InternalOrderBlock iob, Long instrumentToken) {
        double score = 0.0;

        try {
            String iobDirection = "BULLISH_IOB".equals(iob.getObType()) ? "BULLISH" : "BEARISH";
            String iobTimeframe = iob.getTimeframe();

            // Check alignment with higher timeframes
            for (String htf : TIMEFRAME_HIERARCHY) {
                if (isHigherTimeframe(htf, iobTimeframe)) {
                    List<InternalOrderBlock> htfIOBs = iobRepository.findFreshIOBsByType(instrumentToken,
                            iobDirection.equals("BULLISH") ? "BULLISH_IOB" : "BEARISH_IOB");

                    // Filter by timeframe
                    htfIOBs = htfIOBs.stream()
                            .filter(i -> htf.equals(i.getTimeframe()))
                            .toList();

                    if (!htfIOBs.isEmpty()) {
                        // Check if any HTF IOB zone overlaps or is nearby
                        for (InternalOrderBlock htfIOB : htfIOBs) {
                            if (zonesOverlapOrNearby(iob, htfIOB)) {
                                Double weight = TIMEFRAME_WEIGHTS.getOrDefault(htf, 1.0);
                                score += 5.0 * weight; // Add weighted score for confluence
                                logger.debug("MTF confluence found: {} IOB aligned with {} IOB", iobTimeframe, htf);
                            }
                        }
                    }
                }
            }

            // Cap the score
            score = Math.min(score, 25.0);

        } catch (Exception e) {
            logger.error("Error calculating MTF confluence score for IOB {}", iob.getId(), e);
        }

        return score;
    }

    @Override
    public List<InternalOrderBlock> getHTFAlignedIOBs(Long instrumentToken) {
        List<InternalOrderBlock> ltfIOBs = iobRepository.findFreshIOBs(instrumentToken);
        String htfBias = getHTFBias(instrumentToken);

        if ("NEUTRAL".equals(htfBias)) {
            return ltfIOBs; // Return all if no clear HTF bias
        }

        return ltfIOBs.stream()
                .filter(iob -> {
                    String direction = "BULLISH_IOB".equals(iob.getObType()) ? "BULLISH" : "BEARISH";
                    return direction.equals(htfBias);
                })
                .toList();
    }

    @Override
    public boolean isHTFAligned(Long instrumentToken, String tradeDirection) {
        String htfBias = getHTFBias(instrumentToken);
        if ("NEUTRAL".equals(htfBias)) {
            return true; // Neutral bias allows both directions
        }

        // Map trade direction to bias
        String expectedBias = "LONG".equals(tradeDirection) ? "BULLISH" : "BEARISH";
        return expectedBias.equals(htfBias);
    }

    private String getHTFBias(Long instrumentToken) {
        try {
            // Scan 1-hour and daily timeframes
            List<InternalOrderBlock> hourlyIOBs = scanForIOBs(instrumentToken, "1hour");
            List<InternalOrderBlock> dailyIOBs = scanForIOBs(instrumentToken, "daily");

            Map<String, List<InternalOrderBlock>> mtfIOBs = new HashMap<>();
            mtfIOBs.put("1hour", hourlyIOBs);
            mtfIOBs.put("daily", dailyIOBs);

            return determineHTFBias(mtfIOBs);
        } catch (Exception e) {
            logger.error("Error getting HTF bias for token {}", instrumentToken, e);
            return "NEUTRAL";
        }
    }

    private String determineHTFBias(Map<String, List<InternalOrderBlock>> mtfIOBs) {
        double bullishScore = 0;
        double bearishScore = 0;

        for (Map.Entry<String, List<InternalOrderBlock>> entry : mtfIOBs.entrySet()) {
            String timeframe = entry.getKey();
            List<InternalOrderBlock> iobs = entry.getValue();
            Double weight = TIMEFRAME_WEIGHTS.getOrDefault(timeframe, 1.0);

            for (InternalOrderBlock iob : iobs) {
                if ("BULLISH_IOB".equals(iob.getObType())) {
                    bullishScore += weight;
                } else if ("BEARISH_IOB".equals(iob.getObType())) {
                    bearishScore += weight;
                }
            }
        }

        if (bullishScore > bearishScore * 1.2) {
            return "BULLISH";
        } else if (bearishScore > bullishScore * 1.2) {
            return "BEARISH";
        }
        return "NEUTRAL";
    }

    private boolean isHigherTimeframe(String tf1, String tf2) {
        int idx1 = TIMEFRAME_HIERARCHY.indexOf(tf1);
        int idx2 = TIMEFRAME_HIERARCHY.indexOf(tf2);
        return idx1 > idx2;
    }

    private boolean zonesOverlapOrNearby(InternalOrderBlock iob1, InternalOrderBlock iob2) {
        if (iob1.getZoneHigh() == null || iob1.getZoneLow() == null ||
            iob2.getZoneHigh() == null || iob2.getZoneLow() == null) {
            return false;
        }

        // Check for overlap
        boolean overlap = !(iob1.getZoneHigh() < iob2.getZoneLow() || iob1.getZoneLow() > iob2.getZoneHigh());
        if (overlap) return true;

        // Check if zones are nearby (within 1% of each other)
        double zone1Mid = (iob1.getZoneHigh() + iob1.getZoneLow()) / 2;
        double zone2Mid = (iob2.getZoneHigh() + iob2.getZoneLow()) / 2;
        double distance = Math.abs(zone1Mid - zone2Mid) / zone1Mid * 100;

        return distance < 1.0;
    }

    // ==================== Enhancement Methods ====================

    /**
     * Enhance IOB with market structure analysis data
     */
    private void enhanceWithMarketStructure(InternalOrderBlock iob, List<HistoricalCandle> candles) {
        if (marketStructureService == null) {
            logger.debug("MarketStructureService not available, skipping enhancement");
            return;
        }

        try {
            com.trading.kalyani.KPN.entity.MarketStructure structure =
                    marketStructureService.analyzeMarketStructure(iob.getInstrumentToken(), iob.getTimeframe(), candles);

            if (structure != null) {
                iob.setMarketTrend(structure.getTrendDirection());
                iob.setPriceZone(structure.getPriceZone());
                iob.setMarketPhase(structure.getMarketPhase());
                iob.setChochConfluence(structure.getChochDetected());

                // Check if trade aligns with market structure
                boolean aligned = marketStructureService.isTradeAlignedWithStructure(
                        iob.getInstrumentToken(), iob.getTimeframe(), iob.getTradeDirection());
                iob.setTrendAligned(aligned);

                // Calculate structure confluence score
                double score = 50.0;
                if (aligned) score += 20;
                if ("DISCOUNT".equals(structure.getPriceZone()) && "LONG".equals(iob.getTradeDirection())) score += 15;
                if ("PREMIUM".equals(structure.getPriceZone()) && "SHORT".equals(iob.getTradeDirection())) score += 15;
                if (Boolean.TRUE.equals(structure.getChochDetected())) {
                    if (("BULLISH_CHOCH".equals(structure.getChochType()) && "LONG".equals(iob.getTradeDirection())) ||
                        ("BEARISH_CHOCH".equals(structure.getChochType()) && "SHORT".equals(iob.getTradeDirection()))) {
                        score += 20;
                    }
                }
                iob.setStructureConfluenceScore(Math.min(100, score));

                logger.debug("Enhanced IOB with market structure: Trend={}, Zone={}, Aligned={}, Score={}",
                        structure.getTrendDirection(), structure.getPriceZone(), aligned, score);
            }
        } catch (Exception e) {
            logger.warn("Error enhancing IOB {} with market structure", iob.getId(), e);
        }
    }

    /**
     * Enhance IOB with volume profile analysis data
     */
    private void enhanceWithVolumeProfile(InternalOrderBlock iob, List<HistoricalCandle> candles) {
        if (volumeProfileService == null) {
            logger.debug("VolumeProfileService not available, skipping enhancement");
            return;
        }

        try {
            // Get IOB candle volume
            Long iobVolume = findCandleVolume(iob.getObCandleTime(), candles);
            if (iobVolume != null) {
                iob.setIobVolume(iobVolume);
            }

            // Calculate volume ratio
            Double ratio = volumeProfileService.calculateIOBVolumeRatio(iob, candles);
            if (ratio != null) {
                iob.setIobVolumeRatio(ratio);
                iob.setVolumeType(volumeProfileService.getIOBVolumeType(iob, candles));
            }

            // Check displacement volume
            iob.setDisplacementVolumeConfirmed(volumeProfileService.isDisplacementConfirmed(iob, candles));

            // Check POC alignment
            iob.setPocAligned(volumeProfileService.isPOCAlignedWithIOB(iob, candles));

            // Get volume delta direction
            iob.setVolumeDeltaDirection(volumeProfileService.getDeltaDirection(candles));

            // Calculate volume confluence score
            Double confluenceScore = volumeProfileService.calculateVolumeConfluenceScore(iob, candles);
            iob.setVolumeConfluenceScore(confluenceScore);

            logger.debug("Enhanced IOB with volume: Ratio={}, Type={}, Delta={}, Score={}",
                    ratio, iob.getVolumeType(), iob.getVolumeDeltaDirection(), confluenceScore);

        } catch (Exception e) {
            logger.warn("Error enhancing IOB {} with volume profile", iob.getId(), e);
        }
    }

    /**
     * Enhance IOB with risk management calculations
     */
    private void enhanceWithRiskManagement(InternalOrderBlock iob) {
        if (riskManagementService == null) {
            logger.debug("RiskManagementService not available, skipping enhancement");
            return;
        }

        try {
            // Calculate position sizing
            Map<String, Object> sizing = riskManagementService.calculatePositionSizeForIOB(iob);

            if (sizing != null && !sizing.containsKey("error")) {
                Object qty  = sizing.get("calculatedQuantity");
                Object lots = sizing.get("calculatedLots");
                Object risk = sizing.get("actualRiskAmount");
                if (qty  instanceof Number) iob.setPositionSize(((Number) qty).intValue());
                if (lots instanceof Number) iob.setLotCount(((Number) lots).intValue());
                if (risk instanceof Number) iob.setRiskAmount(((Number) risk).doubleValue());
            }

            // Calculate ATR and dynamic stop loss
            Double atr = riskManagementService.calculateATR(iob.getInstrumentToken(), iob.getTimeframe(), 14);
            if (atr != null) {
                iob.setAtrValue(atr);
                Double dynamicSL = riskManagementService.calculateDynamicStopLoss(
                        iob.getInstrumentToken(), iob.getTradeDirection(), iob.getEntryPrice(), 1.5);
                if (dynamicSL != null) {
                    iob.setDynamicStopLoss(dynamicSL);
                }
            }

            // Validate trade risk
            Map<String, Object> validation = riskManagementService.preTradeRiskCheck(iob);
            if (validation != null) {
                Object approved = validation.get("approved");
                if (approved instanceof Boolean) iob.setRiskValidated((Boolean) approved);

                @SuppressWarnings("unchecked")
                List<String> errors = (List<String>) validation.get("errors");
                @SuppressWarnings("unchecked")
                List<String> warnings = (List<String>) validation.get("warnings");

                StringBuilder notes = new StringBuilder();
                if (errors != null && !errors.isEmpty()) {
                    notes.append("Errors: ").append(String.join(", ", errors)).append("; ");
                }
                if (warnings != null && !warnings.isEmpty()) {
                    notes.append("Warnings: ").append(String.join(", ", warnings));
                }
                if (!notes.isEmpty()) {
                    iob.setRiskNotes(notes.toString());
                }
            }

            logger.debug("Enhanced IOB with risk management: Size={}, Lots={}, Risk={}, ATR={}, Validated={}",
                    iob.getPositionSize(), iob.getLotCount(), iob.getRiskAmount(),
                    iob.getAtrValue(), iob.getRiskValidated());

        } catch (Exception e) {
            logger.warn("Error enhancing IOB {} with risk management", iob.getId(), e);
        }
    }

    /**
     * Calculate enhanced confidence score combining all factors
     */
    private void calculateEnhancedConfidence(InternalOrderBlock iob) {
        // Start with base signal confidence
        double baseConfidence = iob.getSignalConfidence() != null ? iob.getSignalConfidence() : 50.0;

        // Weight contributions (now includes FVG validation)
        // Constants intentionally kept local — weights are strategy parameters, not application-wide config
        double baseWeight = 0.40;       // 40%
        double structureWeight = 0.20;  // 20%
        double volumeWeight = 0.20;     // 20%
        double fvgWeight = 0.20;        // 20% - FVG validation score

        double structureScore = iob.getStructureConfluenceScore() != null ? iob.getStructureConfluenceScore() : 50.0;
        double volumeScore = iob.getVolumeConfluenceScore() != null ? iob.getVolumeConfluenceScore() : 50.0;
        double fvgScore = iob.getFvgValidationScore() != null ? iob.getFvgValidationScore() :
                          (Boolean.TRUE.equals(iob.getHasFvg()) ? 50.0 : 30.0);

        double enhancedConfidence =
                (baseConfidence * baseWeight) +
                (structureScore * structureWeight) +
                (volumeScore * volumeWeight) +
                (fvgScore * fvgWeight);

        // Apply bonuses/penalties
        if (Boolean.TRUE.equals(iob.getTrendAligned())) {
            enhancedConfidence += 5;
        } else if (Boolean.FALSE.equals(iob.getTrendAligned())) {
            enhancedConfidence -= 10;
        }

        if ("INSTITUTIONAL".equals(iob.getVolumeType())) {
            enhancedConfidence += 5;
        } else if ("RETAIL".equals(iob.getVolumeType())) {
            enhancedConfidence -= 5;
        }

        if (Boolean.TRUE.equals(iob.getDisplacementVolumeConfirmed())) {
            enhancedConfidence += 5;
        }

        if (Boolean.TRUE.equals(iob.getPocAligned())) {
            enhancedConfidence += 5;
        }

        if (Boolean.FALSE.equals(iob.getRiskValidated())) {
            enhancedConfidence -= 10;
        }

        // FVG validation bonuses/penalties
        if (Boolean.TRUE.equals(iob.getFvgValid())) {
            enhancedConfidence += 5; // Bonus for validated FVG
            // Extra bonus for high-priority FVG
            if (Integer.valueOf(1).equals(iob.getFvgPriority())) {
                enhancedConfidence += 3; // Top priority FVG
            }
        } else if (Boolean.TRUE.equals(iob.getHasFvg()) && Boolean.FALSE.equals(iob.getFvgValid())) {
            enhancedConfidence -= 3; // Penalty for invalid FVG (FVG present but failed validation)
        }

        // Clamp to 0-100
        enhancedConfidence = Math.max(0, Math.min(100, enhancedConfidence));
        iob.setEnhancedConfidence(enhancedConfidence);

        logger.debug("Calculated enhanced confidence: Base={}, Structure={}, Volume={}, FVG={}, Enhanced={}",
                baseConfidence, structureScore, volumeScore, fvgScore, enhancedConfidence);
    }

    /**
     * Find volume for a candle at specific time
     */
    private Long findCandleVolume(LocalDateTime candleTime, List<HistoricalCandle> candles) {
        if (candleTime == null || candles == null) return null;

        for (HistoricalCandle candle : candles) {
            LocalDateTime time = parseTimestamp(candle.getTimestamp());
            if (time != null && time.equals(candleTime)) {
                return candle.getVolume();
            }
        }
        return null;
    }


    /** Returns "Label ✅" or "Label ❌" based on a nullable Boolean flag. */
    private static String fvgFlag(Boolean flag, String label) {
        return label + (Boolean.TRUE.equals(flag) ? " ✅" : " ❌");
    }

    /** Returns true if any swing point before {@code beforeIndex} falls within [low, high]. */
    private boolean anySwingInRange(List<SwingPoint> swings, int beforeIndex, double low, double high) {
        for (SwingPoint sp : swings) {
            if (sp.index < beforeIndex && sp.price >= low && sp.price <= high) return true;
        }
        return false;
    }

    /**
     * Inner class to represent swing points
     */
    private static class SwingPoint {
        int index;
        double price;

        SwingPoint(int index, double price) {
            this.index = index;
            this.price = price;
        }
    }
}
