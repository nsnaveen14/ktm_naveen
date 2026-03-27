package com.trading.kalyani.KTManager.service.serviceImpl;

import com.trading.kalyani.KTManager.entity.CandleStick;
import com.trading.kalyani.KTManager.repository.CandleStickRepository;
import com.trading.kalyani.KTManager.service.ZeroHeroSignalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Zero Hero Signal Service — expiry day afternoon strategy.
 *
 * Logic:
 *  1. Only fires on Thursday (NIFTY weekly expiry day)
 *  2. Only between 14:30 and 15:10
 *  3. Determines direction via 3 momentum indicators (EMA trend, price momentum, RSI level)
 *  4. Selects the right OTM option whose current premium is in the "near zero" range (5–50 pts)
 *  5. Fires when at least 2 of 3 indicators agree AND premium is in range
 *
 * Strike selection:
 *  - BUY  (CALL): ATM + 1 strike (50 pts OTM). If premium > MAX, try ATM + 2.
 *  - SELL (PUT) : ATM - 1 strike (50 pts OTM). If premium > MAX, try ATM - 2.
 *
 * Targets/SL:
 *  - Target     : 3× entry premium  (true zero-to-hero)
 *  - Stop Loss  : 50% of entry premium
 *  - Hard exit  : 15:25 IST — handled by SimulatedTradingServiceImpl time guard
 */
@Service
public class ZeroHeroSignalServiceImpl implements ZeroHeroSignalService {

    private static final Logger logger = LoggerFactory.getLogger(ZeroHeroSignalServiceImpl.class);

    private static final ZoneId  IST               = ZoneId.of("Asia/Kolkata");
    private static final LocalTime WINDOW_START     = LocalTime.of(14, 30);
    private static final LocalTime WINDOW_END       = LocalTime.of(15, 10);

    // ── Premium gate — "near zero" range ────────────────────────────────────
    private static final double MIN_PREMIUM              = 5.0;   // below this → worthless / illiquid
    private static final double MAX_PREMIUM_EARLY        = 50.0;  // 14:30–15:00: standard gate
    private static final double MAX_PREMIUM_LATE         = 80.0;  // 15:00–15:10: ATM can still be cheap after rapid decay

    // ── Trade parameters ─────────────────────────────────────────────────────
    // Target multiplier decays as time runs out — less room for a 3× move after 3 PM
    private static final double TARGET_MULTIPLIER_EARLY  = 3.0;   // before 15:00
    private static final double TARGET_MULTIPLIER_LATE   = 2.0;   // 15:00 onwards
    private static final LocalTime LATE_WINDOW_START     = LocalTime.of(15, 0);
    private static final double SL_PCT                   = 0.50;  // stop loss = 50% of entry
    private static final int    NIFTY_STRIKE_GAP         = 50;    // NIFTY options are in 50-point gaps

    // ── Indicator parameters ─────────────────────────────────────────────────
    private static final int    EMA_SHORT           = 9;
    private static final int    EMA_LONG            = 21;
    private static final int    RSI_PERIOD          = 14;
    private static final int    MOMENTUM_BARS       = 6;     // last 6 closed 1-min bars for price momentum
    // 4 out of 6 bars (67%) is sufficient — avoids missing valid signals with 1-2 blip candles
    private static final double MOMENTUM_RATIO      = 0.67;
    private static final int    CANDLE_LOOKBACK     = 60;
    private static final int    MIN_CANDLES         = EMA_LONG + RSI_PERIOD + 2; // 37

    private static final double RSI_BULL_THRESHOLD  = 55.0;  // RSI > 55 → bullish momentum
    private static final double RSI_BEAR_THRESHOLD  = 45.0;  // RSI < 45 → bearish momentum

    private static final Long   NIFTY_TOKEN         = 256265L;

    @Autowired(required = false)
    private CandleStickRepository candleStickRepository;

    // ── Public API ───────────────────────────────────────────────────────────

    @Override
    public Map<String, Object> checkSignal(Map<String, Object> liveData) {
        Map<String, Object> result = new HashMap<>();
        result.put("hasSignal", false);

        try {
            // 1. Expiry day guard — NIFTY weekly expires on Thursday
            if (!isExpiryDay()) {
                logger.debug("ZeroHero: not expiry day — skipping");
                return result;
            }

            // 2. Time window guard
            LocalTime now = LocalTime.now(IST);
            if (now.isBefore(WINDOW_START) || now.isAfter(WINDOW_END)) {
                logger.debug("ZeroHero: outside window ({} – {}) — current {}", WINDOW_START, WINDOW_END, now);
                return result;
            }

            // 3. Get NIFTY LTP and ATM strike from live data
            Double niftyLTP = toDouble(liveData, "niftyLTP");
            Integer atmStrike = toInt(liveData, "atmStrike");
            if (niftyLTP == null || atmStrike == null || niftyLTP <= 0) {
                logger.debug("ZeroHero: missing live data niftyLTP={} atmStrike={}", niftyLTP, atmStrike);
                return result;
            }

            // 4. Candle-based momentum analysis
            if (candleStickRepository == null) return result;
            List<CandleStick> raw = candleStickRepository.findRecentCandles(NIFTY_TOKEN, CANDLE_LOOKBACK);
            if (raw == null || raw.size() < MIN_CANDLES) {
                logger.debug("ZeroHero: insufficient candles ({}/{})", raw == null ? 0 : raw.size(), MIN_CANDLES);
                return result;
            }

            List<CandleStick> candles = new ArrayList<>(raw);
            candles.sort(Comparator.comparing(CandleStick::getCandleStartTime));
            // Drop live (incomplete) candle — same guard as GainzAlgo
            candles = candles.subList(0, candles.size() - 1);

            List<Double> prices = toClosePrices(candles);

            // 5. Compute indicators
            double ema9      = computeEMA(prices, EMA_SHORT);
            double ema21     = computeEMA(prices, EMA_LONG);
            double rsi       = computeRSI(prices, RSI_PERIOD);
            boolean priceMomentumBull = isPriceMomentumBullish(candles);
            boolean priceMomentumBear = isPriceMomentumBearish(candles);

            // 6. Score momentum (0–3 indicators)
            int bullScore = 0;
            int bearScore = 0;

            if (ema9 > ema21) bullScore++; else bearScore++;
            if (rsi > RSI_BULL_THRESHOLD) bullScore++; else if (rsi < RSI_BEAR_THRESHOLD) bearScore++;
            if (priceMomentumBull) bullScore++; else if (priceMomentumBear) bearScore++;

            logger.debug("ZeroHero: EMA9={} EMA21={} RSI={} bullScore={} bearScore={}",
                    String.format("%.2f", ema9), String.format("%.2f", ema21),
                    String.format("%.2f", rsi), bullScore, bearScore);

            // Need at least 2 out of 3 to agree
            int score;
            String signalType;
            String optionType;
            if (bullScore >= 2 && bullScore > bearScore) {
                signalType = "BUY";
                optionType = "CE";
                score = bullScore;
            } else if (bearScore >= 2 && bearScore > bullScore) {
                signalType = "SELL";
                optionType = "PE";
                score = bearScore;
            } else {
                logger.debug("ZeroHero: conflicting momentum — bull={} bear={} — no signal", bullScore, bearScore);
                return result;
            }

            // 7. Strike + premium selection — find OTM option in the near-zero range
            boolean isLate = now.isAfter(LATE_WINDOW_START);
            double maxPremium = isLate ? MAX_PREMIUM_LATE : MAX_PREMIUM_EARLY;
            double targetMultiplier = isLate ? TARGET_MULTIPLIER_LATE : TARGET_MULTIPLIER_EARLY;

            OtmOption option = resolveOtmOption(liveData, optionType, atmStrike, maxPremium);
            if (option == null) {
                logger.debug("ZeroHero: no OTM option found in premium range [{}-{}] for {}",
                        MIN_PREMIUM, maxPremium, optionType);
                return result;
            }

            // 8. Confidence score: (score/3 × 60) + premium position bonus (up to 20) + 20 base
            double premiumPositionScore = Math.max(0, 1.0 - (option.ltp / maxPremium)) * 20.0;
            int confidence = (int) Math.min(100, 20 + (score / 3.0 * 60) + premiumPositionScore);

            // 9. Build result
            String signalStrength = score == 3 ? "STRONG" : "MODERATE";
            double target   = option.ltp * targetMultiplier;
            double stopLoss = option.ltp * (1.0 - SL_PCT);

            result.put("hasSignal",     true);
            result.put("signalType",    signalType);
            result.put("signalStrength",signalStrength);
            result.put("optionType",    optionType);
            result.put("strikePrice",   option.strike);
            result.put("optionPremium", option.ltp);
            result.put("targetPrice",   target);
            result.put("stopLossPrice", stopLoss);
            result.put("momentumScore", score);
            result.put("confidence",    confidence);
            result.put("ema9",          ema9);
            result.put("ema21",         ema21);
            result.put("rsi",           rsi);

            logger.info("ZeroHero signal: {} {} strike={} premium={} target={} SL={} score={}/3",
                    signalType, optionType, option.strike,
                    String.format("%.2f", option.ltp),
                    String.format("%.2f", target),
                    String.format("%.2f", stopLoss),
                    score);

        } catch (Exception e) {
            logger.error("ZeroHero: error computing signal: {}", e.getMessage(), e);
        }

        return result;
    }

    // ── Expiry day detection ─────────────────────────────────────────────────

    /**
     * NIFTY weekly options expire every Tuesday.
     * If Tuesday is a market holiday, expiry moves to Monday — that edge case
     * is rare and handled by ops team; we simply check for Tuesday here.
     */
    private boolean isExpiryDay() {
        return LocalDate.now(IST).getDayOfWeek() == DayOfWeek.TUESDAY;
    }

    // ── OTM option resolution ────────────────────────────────────────────────

    /**
     * Try ATM+1 first, then ATM+2 (for CE) / ATM-1, ATM-2 (for PE).
     * Returns the first strike whose live premium is in [MIN_PREMIUM, MAX_PREMIUM].
     * Returns null if neither is in range (nothing to trade).
     */
    private OtmOption resolveOtmOption(Map<String, Object> liveData, String optionType, int atmStrike, double maxPremium) {
        boolean isCE = "CE".equals(optionType);

        // OTM+1 and OTM+2 strikes
        int strike1 = isCE ? atmStrike + NIFTY_STRIKE_GAP     : atmStrike - NIFTY_STRIKE_GAP;
        int strike2 = isCE ? atmStrike + NIFTY_STRIKE_GAP * 2 : atmStrike - NIFTY_STRIKE_GAP * 2;

        // Try OTM+1 first
        Double ltp1 = resolveStrikeLTP(liveData, optionType, strike1);
        if (ltp1 != null && ltp1 >= MIN_PREMIUM && ltp1 <= maxPremium) {
            return new OtmOption(strike1, ltp1);
        }

        // Try OTM+2 if OTM+1 was too expensive
        if (ltp1 != null && ltp1 > maxPremium) {
            Double ltp2 = resolveStrikeLTP(liveData, optionType, strike2);
            if (ltp2 != null && ltp2 >= MIN_PREMIUM && ltp2 <= maxPremium) {
                return new OtmOption(strike2, ltp2);
            }
        }

        // Last resort: ATM option itself (when near-expiry ATM can also be cheap)
        Double atmLTP = toDouble(liveData, isCE ? "atmCELTP" : "atmPELTP");
        if (atmLTP != null && atmLTP >= MIN_PREMIUM && atmLTP <= maxPremium) {
            return new OtmOption(atmStrike, atmLTP);
        }

        return null;
    }

    /**
     * Reads OTM option LTP from live data map.
     * Live data keys convention (set by CandlePredictionService / KiteTickerProvider):
     *   otmCE_23300_LTP, otmPE_23200_LTP, etc.
     * Falls back to atmCELTP/atmPELTP for ATM.
     */
    private Double resolveStrikeLTP(Map<String, Object> liveData, String optionType, int strike) {
        String key = String.format("otm%s_%d_LTP", optionType, strike);
        Double ltp = toDouble(liveData, key);
        if (ltp != null) return ltp;

        // Fallback: check if the ATM key matches this strike (ATM shifts during the day)
        Integer atmStrike = toInt(liveData, "atmStrike");
        if (atmStrike != null && atmStrike == strike) {
            return toDouble(liveData, "CE".equals(optionType) ? "atmCELTP" : "atmPELTP");
        }

        return null;
    }

    // ── Momentum helpers ─────────────────────────────────────────────────────

    /**
     * Price momentum: last N closed candles making consistently higher closes.
     * At least (N-1) out of N candles must close higher than the previous.
     */
    private boolean isPriceMomentumBullish(List<CandleStick> candles) {
        int n = Math.min(MOMENTUM_BARS, candles.size());
        int start = candles.size() - n;
        int upCount = 0;
        for (int i = start + 1; i < candles.size(); i++) {
            Double curr = candles.get(i).getClosePrice();
            Double prev = candles.get(i - 1).getClosePrice();
            if (curr != null && prev != null && curr > prev) upCount++;
        }
        return upCount >= (int) Math.ceil((n - 1) * MOMENTUM_RATIO); // e.g. 4 out of 6 bars up
    }

    private boolean isPriceMomentumBearish(List<CandleStick> candles) {
        int n = Math.min(MOMENTUM_BARS, candles.size());
        int start = candles.size() - n;
        int downCount = 0;
        for (int i = start + 1; i < candles.size(); i++) {
            Double curr = candles.get(i).getClosePrice();
            Double prev = candles.get(i - 1).getClosePrice();
            if (curr != null && prev != null && curr < prev) downCount++;
        }
        return downCount >= (int) Math.ceil((n - 1) * MOMENTUM_RATIO);
    }

    // ── Indicator math ───────────────────────────────────────────────────────

    private List<Double> toClosePrices(List<CandleStick> candles) {
        List<Double> prices = new ArrayList<>(candles.size());
        for (CandleStick c : candles) {
            prices.add(c.getClosePrice() != null ? c.getClosePrice() : 0.0);
        }
        return prices;
    }

    private double computeEMA(List<Double> prices, int period) {
        double multiplier = 2.0 / (period + 1);
        double ema = 0.0;
        for (int i = 0; i < period; i++) ema += prices.get(i);
        ema /= period;
        for (int i = period; i < prices.size(); i++) {
            ema = (prices.get(i) - ema) * multiplier + ema;
        }
        return ema;
    }

    private double computeRSI(List<Double> prices, int period) {
        if (prices.size() <= period) return 50.0;
        double avgGain = 0.0, avgLoss = 0.0;
        for (int i = 1; i <= period; i++) {
            double change = prices.get(i) - prices.get(i - 1);
            if (change > 0) avgGain += change;
            else            avgLoss += Math.abs(change);
        }
        avgGain /= period;
        avgLoss /= period;
        for (int i = period + 1; i < prices.size(); i++) {
            double change = prices.get(i) - prices.get(i - 1);
            double gain = change > 0 ? change : 0.0;
            double loss = change < 0 ? Math.abs(change) : 0.0;
            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
        }
        if (avgLoss == 0.0) return 100.0;
        return 100.0 - (100.0 / (1.0 + avgGain / avgLoss));
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static Double toDouble(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : null;
    }

    private static Integer toInt(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Number ? ((Number) v).intValue() : null;
    }

    private record OtmOption(int strike, double ltp) {}
}
