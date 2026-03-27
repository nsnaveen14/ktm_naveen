package com.trading.kalyani.KTManager.service.serviceImpl;

import com.trading.kalyani.KTManager.entity.CandleStick;
import com.trading.kalyani.KTManager.repository.CandleStickRepository;
import com.trading.kalyani.KTManager.service.GainzAlgoSignalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GainzAlgo v2 Alpha signal source.
 *
 * Strategy: Pullback-resumption within an established EMA trend, confirmed by RSI momentum pivot.
 *
 * BUY:  EMA9 > EMA21  AND  prev_RSI ≤ 48  AND  curr_RSI > 48  (pullback absorbed, momentum turning up)
 * SELL: EMA9 < EMA21  AND  prev_RSI ≥ 52  AND  curr_RSI < 52  (bounce rejected, momentum turning down)
 *
 * Signal strength:
 *   RSI delta ≥ 5 → STRONG
 *   RSI delta ≥ 2 → MODERATE
 *   RSI delta < 2 → no signal (too weak / noise)
 */
@Service
public class GainzAlgoSignalServiceImpl implements GainzAlgoSignalService {

    private static final Logger logger = LoggerFactory.getLogger(GainzAlgoSignalServiceImpl.class);

    // ── Indicator parameters ────────────────────────────────────────────────
    private static final int    EMA_SHORT            = 9;
    private static final int    EMA_LONG             = 21;
    private static final int    RSI_PERIOD           = 14;
    private static final int    CANDLE_LOOKBACK      = 60;   // last 60 1-min candles
    private static final int    MIN_CANDLES_REQUIRED = EMA_LONG + RSI_PERIOD + 2; // 37

    // ── Signal thresholds ───────────────────────────────────────────────────
    private static final double RSI_BUY_THRESHOLD  = 48.0;  // RSI pivot zone for BUY
    private static final double RSI_SELL_THRESHOLD = 52.0;  // RSI pivot zone for SELL
    private static final double RSI_STRONG_DELTA   = 5.0;   // STRONG if RSI delta ≥ this
    private static final double RSI_MIN_DELTA      = 4.0;   // ignore if RSI delta < this (raised: 2→4 to filter noise)
    private static final double EMA_MIN_SPREAD_PCT = 0.05;  // require EMA9/EMA21 spread ≥ 0.05% (trend must be established)

    // ── NIFTY instrument token ──────────────────────────────────────────────
    private static final Long NIFTY_TOKEN = 256265L;

    @Autowired(required = false)
    private CandleStickRepository candleStickRepository;

    // ── Public API ──────────────────────────────────────────────────────────

    @Override
    public Map<String, Object> checkSignal() {
        Map<String, Object> result = new HashMap<>();
        result.put("hasSignal", false);

        try {
            if (candleStickRepository == null) {
                return result;
            }

            // 1. Fetch and sort recent candles ascending
            List<CandleStick> raw = candleStickRepository.findRecentCandles(NIFTY_TOKEN, CANDLE_LOOKBACK);
            if (raw == null || raw.size() < MIN_CANDLES_REQUIRED) {
                logger.debug("GainzAlgo: insufficient candles ({}/{})", raw == null ? 0 : raw.size(), MIN_CANDLES_REQUIRED);
                return result;
            }
            List<CandleStick> candles = new ArrayList<>(raw);
            candles.sort(Comparator.comparing(CandleStick::getCandleStartTime));

            // Drop the last (newest) candle — it is still forming and its close is unreliable.
            // All indicator math must use only fully closed candles.
            candles = candles.subList(0, candles.size() - 1);

            // 2. Extract close prices
            List<Double> prices = toClosePrices(candles);

            // 3. Compute EMAs
            double ema9  = calculateCurrentEMA(prices, EMA_SHORT);
            double ema21 = calculateCurrentEMA(prices, EMA_LONG);

            // 4. Compute RSI for current and previous bar
            double currentRSI  = calculateCurrentRSI(prices, RSI_PERIOD);
            double previousRSI = calculatePreviousRSI(prices, RSI_PERIOD);

            // 5. Trend filter — require meaningful EMA separation (avoids ranging/choppy markets)
            double emaSeparationPct = Math.abs(ema9 - ema21) / ema21 * 100.0;
            if (emaSeparationPct < EMA_MIN_SPREAD_PCT) {
                logger.debug("GainzAlgo: EMA spread {}% below minimum {}% — ranging market, no signal",
                        String.format("%.4f", emaSeparationPct), EMA_MIN_SPREAD_PCT);
                return result;
            }
            boolean bullish = ema9 > ema21;
            boolean bearish = ema9 < ema21;

            // 6. Signal detection
            boolean buySignal  = bullish && previousRSI <= RSI_BUY_THRESHOLD  && currentRSI > RSI_BUY_THRESHOLD;
            boolean sellSignal = bearish && previousRSI >= RSI_SELL_THRESHOLD && currentRSI < RSI_SELL_THRESHOLD;

            if (!buySignal && !sellSignal) {
                logger.debug("GainzAlgo: no signal — EMA9={} EMA21={} RSI curr={} prev={}",
                        String.format("%.2f", ema9), String.format("%.2f", ema21),
                        String.format("%.2f", currentRSI), String.format("%.2f", previousRSI));
                return result;
            }

            // 7. Strength filter — require minimum delta to avoid noise
            double delta = Math.abs(currentRSI - previousRSI);
            if (delta < RSI_MIN_DELTA) {
                logger.debug("GainzAlgo: RSI delta {} below minimum {} — suppressing signal",
                        String.format("%.2f", delta), RSI_MIN_DELTA);
                return result;
            }

            String signalType     = buySignal ? "BUY" : "SELL";
            String signalStrength = delta >= RSI_STRONG_DELTA ? "STRONG" : "MODERATE";

            result.put("hasSignal",     true);
            result.put("signalType",    signalType);
            result.put("signalStrength",signalStrength);
            result.put("ema9",          ema9);
            result.put("ema21",         ema21);
            result.put("rsi",           currentRSI);

            logger.debug("GainzAlgo signal: {} ({}) — RSI={} (delta={}), EMA9={}, EMA21={}",
                    signalType, signalStrength,
                    String.format("%.2f", currentRSI), String.format("%.2f", delta),
                    String.format("%.2f", ema9), String.format("%.2f", ema21));

        } catch (Exception e) {
            logger.error("GainzAlgo: error computing signal: {}", e.getMessage(), e);
            result.put("error", e.getMessage());
        }

        return result;
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private List<Double> toClosePrices(List<CandleStick> candles) {
        List<Double> prices = new ArrayList<>(candles.size());
        for (CandleStick c : candles) {
            prices.add(c.getClosePrice() != null ? c.getClosePrice() : 0.0);
        }
        return prices;
    }

    /**
     * Calculates EMA at the last index of the price list using standard exponential smoothing.
     * Seed: SMA of first {@code period} values.
     */
    private double calculateCurrentEMA(List<Double> prices, int period) {
        return computeEMA(prices, period, prices.size() - 1);
    }

    private double computeEMA(List<Double> prices, int period, int endIndex) {
        double multiplier = 2.0 / (period + 1);
        // seed with SMA of first {period} values
        double ema = 0.0;
        for (int i = 0; i < period; i++) {
            ema += prices.get(i);
        }
        ema /= period;
        for (int i = period; i <= endIndex; i++) {
            ema = (prices.get(i) - ema) * multiplier + ema;
        }
        return ema;
    }

    /**
     * Calculates RSI (Wilder's smoothed) at the last bar.
     * Seed: average gain/loss over first {@code period} changes.
     */
    private double calculateCurrentRSI(List<Double> prices, int period) {
        return computeRSI(prices, period, prices.size() - 1);
    }

    /**
     * Calculates RSI (Wilder's smoothed) at the previous bar (second-to-last).
     */
    private double calculatePreviousRSI(List<Double> prices, int period) {
        return computeRSI(prices, period, prices.size() - 2);
    }

    private double computeRSI(List<Double> prices, int period, int endIndex) {
        // Need at least period+1 prices to compute period changes
        if (endIndex < period) return 50.0; // neutral fallback

        // Seed: average gain / average loss over first {period} changes
        double avgGain = 0.0;
        double avgLoss = 0.0;
        for (int i = 1; i <= period; i++) {
            double change = prices.get(i) - prices.get(i - 1);
            if (change > 0) avgGain += change;
            else            avgLoss += Math.abs(change);
        }
        avgGain /= period;
        avgLoss /= period;

        // Wilder's smoothing from period+1 up to endIndex
        for (int i = period + 1; i <= endIndex; i++) {
            double change = prices.get(i) - prices.get(i - 1);
            double gain   = change > 0 ? change : 0.0;
            double loss   = change < 0 ? Math.abs(change) : 0.0;
            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
        }

        if (avgLoss == 0.0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }
}
