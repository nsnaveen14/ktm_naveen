package com.trading.kalyani.KTManager.service.serviceImpl;

import com.trading.kalyani.KTManager.entity.CandleStick;
import com.trading.kalyani.KTManager.repository.CandleStickRepository;
import com.trading.kalyani.KTManager.service.CandlePredictionService;
import com.trading.kalyani.KTManager.service.EMACrossoverSignalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * EMA Crossover breakout signal source.
 *
 * Two-stage confirmation to avoid trading on the crossover candle alone:
 *   Stage A: fresh EMA crossover → arm the recent swing high (BUY) or swing low (SELL)
 *            as a pending breakout level.
 *   Stage B: live LTP crosses that level → fire the trade signal.
 *            If a nearby FVG exists, signal strength is upgraded to STRONG.
 *
 * Pending state expires after EMA_BREAKOUT_EXPIRY_MINUTES or if alignment reverses.
 * State is cleared by clearPendingState(), which must only be called from the
 * trade placement path — never from read-only UI polling threads.
 */
@Service
public class EMACrossoverSignalServiceImpl implements EMACrossoverSignalService {

    private static final Logger logger = LoggerFactory.getLogger(EMACrossoverSignalServiceImpl.class);

    // FVG confirmation: minimum gap size in NIFTY points (ignores sub-noise gaps)
    private static final double FVG_MIN_SIZE_POINTS    = 3.0;
    private static final Long   FVG_NIFTY_TOKEN        = 256265L;
    private static final int    EMA_BREAKOUT_EXPIRY_MINUTES = 30;

    // Stage A/B pending state — session-level, intraday only
    private volatile String        pendingDirection = null;  // "BUY" or "SELL"
    private volatile double        pendingLevel     = 0.0;   // swing high (BUY) or swing low (SELL)
    private volatile LocalDateTime pendingSetAt     = null;  // timestamp when Stage A was armed

    @Lazy
    @Autowired
    private CandlePredictionService candlePredictionService;

    @Autowired(required = false)
    private CandleStickRepository candleStickRepository;

    @Override
    public Map<String, Object> checkSignal() {
        Map<String, Object> result = new HashMap<>();
        result.put("hasSignal", false);

        try {
            Map<String, Object> emaData = candlePredictionService.getEMAChartData();
            if (emaData == null || emaData.containsKey("error")) return result;

            String emaAlignment = (String) emaData.get("emaAlignment");
            String tradeSignal  = (String) emaData.get("tradeSignal"); // BUY/SELL only on crossover candle

            if (emaAlignment == null) return result;

            String signalType     = "BULLISH".equals(emaAlignment) ? "BUY" : "SELL";
            String signalStrength = signalType.equals(tradeSignal) ? "STRONG" : "MODERATE";

            logger.debug("EMA - type: {}, strength: {}, alignment: {}", signalType, signalStrength, emaAlignment);

            // Stage A: fresh crossover → arm the pending swing level
            boolean freshCrossover = signalType.equals(tradeSignal);
            if (freshCrossover && pendingDirection == null) {
                Double swingHigh = toDouble(emaData, "recentSwingHigh");
                Double swingLow  = toDouble(emaData, "recentSwingLow");
                boolean levelAvailable = "BUY".equals(signalType)
                        ? (swingHigh != null && swingHigh > 0)
                        : (swingLow  != null && swingLow  > 0);
                if (levelAvailable) {
                    pendingDirection = signalType;
                    pendingLevel     = "BUY".equals(signalType) ? swingHigh : swingLow;
                    pendingSetAt     = LocalDateTime.now();
                    logger.info("EMA {} crossover: Stage A armed — waiting for breakout {} {:.2f} (recent swing)",
                            signalType, "BUY".equals(signalType) ? "above" : "below", pendingLevel);
                    return result; // no trade yet
                }
                // Level unavailable (< 10 candles in session) — fire immediately
                logger.warn("EMA {} crossover: swing level unavailable — firing immediately (market just opened)", signalType);
            }

            // Stage B: check if live LTP has crossed the pending level
            if (pendingDirection != null) {
                boolean alignmentReversed = !signalType.equals(pendingDirection);
                boolean expired = pendingSetAt != null &&
                        LocalDateTime.now().isAfter(pendingSetAt.plusMinutes(EMA_BREAKOUT_EXPIRY_MINUTES));

                if (alignmentReversed || expired) {
                    logger.info("EMA pending {} breakout cleared: {} (level was {:.2f})",
                            pendingDirection,
                            alignmentReversed ? "alignment reversed" : "expired after 30 min",
                            pendingLevel);
                    pendingDirection = null;
                    pendingLevel     = 0.0;
                    pendingSetAt     = null;
                    return result;
                }

                Double liveLTP = toDouble(candlePredictionService.getLiveTickData(), "niftyLTP");
                if (liveLTP == null || liveLTP <= 0) return result;

                boolean broken = "BUY".equals(pendingDirection)
                        ? liveLTP > pendingLevel
                        : liveLTP < pendingLevel;

                if (broken) {
                    // NOTE: do NOT clear pendingDirection here.
                    // This method is called by both the scheduler and UI polling threads.
                    // Clearing state here would let a UI poll consume the breakout before
                    // processNewSignals() places the trade. State is cleared via clearPendingState().
                    logger.info("EMA {} breakout confirmed: LTP {:.2f} crossed {:.2f} — Stage B arming signal",
                            pendingDirection, liveLTP, pendingLevel);
                    Double  fvgMidpoint  = detectNearbyFVG(pendingDirection);
                    boolean fvgConfirmed = fvgMidpoint != null;
                    result.put("hasSignal",     true);
                    result.put("signalType",    pendingDirection);
                    result.put("signalStrength", fvgConfirmed ? "STRONG" : "MODERATE");
                    result.put("fvgConfirmed",  fvgConfirmed);
                    result.put("emaAlignment",  emaAlignment);
                    result.put("ema9",          emaData.get("currentEMA9"));
                    result.put("ema21",         emaData.get("currentEMA21"));
                    result.put("breakoutLevel", pendingLevel);
                    if (fvgConfirmed) {
                        result.put("fvgMidpoint", fvgMidpoint);
                        logger.info("EMA {} breakout + FVG confirmed at midpoint {:.2f} — STRONG",
                                pendingDirection, fvgMidpoint);
                    }
                } else {
                    logger.debug("EMA {} pending: LTP {:.2f} has not yet crossed level {:.2f} — waiting",
                            pendingDirection, liveLTP, pendingLevel);
                }
                return result;
            }

            logger.debug("EMA: alignment={} — no fresh crossover, no pending breakout, skipping", emaAlignment);

        } catch (Exception e) {
            logger.error("Error checking EMA crossover signal: {}", e.getMessage(), e);
        }

        return result;
    }

    @Override
    public void clearPendingState() {
        pendingDirection = null;
        pendingLevel     = 0.0;
        pendingSetAt     = null;
    }

    /**
     * Scans the last 10 closed 1-min candles for a Fair Value Gap in the given direction.
     * Bullish FVG: candle[i+2].low > candle[i].high  (uncontested gap above candle i)
     * Bearish FVG: candle[i+2].high < candle[i].low  (uncontested gap below candle i)
     * Returns the FVG midpoint if found, null if no qualifying gap exists.
     */
    private Double detectNearbyFVG(String direction) {
        if (candleStickRepository == null) return null;
        try {
            List<CandleStick> raw = candleStickRepository.findRecentCandles(FVG_NIFTY_TOKEN, 10);
            if (raw == null || raw.size() < 3) return null;

            List<CandleStick> candles = new ArrayList<>(raw);
            candles.sort(Comparator.comparing(CandleStick::getCandleStartTime));
            candles = candles.subList(0, candles.size() - 1); // drop in-progress candle

            boolean bullish = "BUY".equals(direction);
            for (int i = candles.size() - 3; i >= 0; i--) {
                double high1 = safe(candles.get(i).getHighPrice());
                double low1  = safe(candles.get(i).getLowPrice());
                double low3  = safe(candles.get(i + 2).getLowPrice());
                double high3 = safe(candles.get(i + 2).getHighPrice());

                if (bullish && low3 > high1 && (low3 - high1) >= FVG_MIN_SIZE_POINTS)
                    return (high1 + low3) / 2.0;
                if (!bullish && high3 < low1 && (low1 - high3) >= FVG_MIN_SIZE_POINTS)
                    return (low1 + high3) / 2.0;
            }
        } catch (Exception e) {
            logger.debug("FVG detection failed: {}", e.getMessage());
        }
        return null;
    }

    private static double safe(Double v) { return v != null ? v : 0.0; }

    private static Double toDouble(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object v = map.get(key);
        if (v instanceof Number n) return n.doubleValue();
        return null;
    }
}
