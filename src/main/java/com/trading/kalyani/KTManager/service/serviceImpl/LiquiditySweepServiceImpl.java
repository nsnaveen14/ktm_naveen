package com.trading.kalyani.KTManager.service.serviceImpl;

import com.trading.kalyani.KTManager.entity.LiquiditySweepAnalysis;
import com.trading.kalyani.KTManager.model.HistoricalDataRequest;
import com.trading.kalyani.KTManager.model.HistoricalDataResponse;
import com.trading.kalyani.KTManager.repository.LiquiditySweepRepository;
import com.trading.kalyani.KTManager.service.InstrumentService;
import com.trading.kalyani.KTManager.service.LiquiditySweepService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

import static com.trading.kalyani.KTManager.constants.ApplicationConstants.*;

/**
 * Implementation of Liquidity Sweep Analysis Service.
 *
 * Implements the "Liquidity Sweep Pro [Whale Edition]" strategy with three synchronized layers:
 *
 * 1. MARKET STRUCTURE (Liquidity Pools)
 *    - Identifies BSL (Buy Side Liquidity) above swing highs
 *    - Identifies SSL (Sell Side Liquidity) below swing lows
 *
 * 2. QUANT ENGINE (Whale Detection)
 *    - Log-Normal Z-Score for volume anomaly detection
 *    - Kaufman Efficiency Ratio for price movement quality
 *    - Classifies whale type: Absorption (Iceberg) or Propulsion (Drive)
 *
 * 3. SMART ENTRY (Trade Signal)
 *    - Price sweeps liquidity level
 *    - Price closes back within range
 *    - Institutional activity confirmed
 *    - Trend and momentum filters aligned
 */
@Service
public class LiquiditySweepServiceImpl implements LiquiditySweepService {

    private static final Logger logger = LoggerFactory.getLogger(LiquiditySweepServiceImpl.class);

    // Configuration defaults
    private static final double DEFAULT_WHALE_THRESHOLD = 2.5;  // 2.5 sigma for whale detection
    private static final int DEFAULT_LOOKBACK_PERIOD = 20;      // Candles for swing detection
    private static final int DEFAULT_VOLUME_PERIOD = 20;        // Period for volume analysis
    private static final int DEFAULT_EMA_PERIOD = 50;           // EMA period for trend (200 exceeds 5-day 15-min data ~125 candles)
    private static final int DEFAULT_RSI_PERIOD = 14;           // RSI period
    private static final int    DEFAULT_ATR_PERIOD     = 14;    // ATR period
    private static final double DEFAULT_ATR_MULTIPLIER = 1.5;   // ATR multiplier for SL (no swept level)
    private static final double DEFAULT_RISK_REWARD = 2.0;      // Default R:R ratio

    // Kaufman Efficiency thresholds
    private static final double KER_ABSORPTION_THRESHOLD = 0.3; // Low efficiency = absorption
    private static final double KER_PROPULSION_THRESHOLD = 0.7; // High efficiency = propulsion

    // RSI thresholds
    private static final double RSI_OVERSOLD = 30.0;
    private static final double RSI_OVERBOUGHT = 70.0;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // Configuration (can be adjusted at runtime)
    private volatile double whaleThreshold = DEFAULT_WHALE_THRESHOLD;
    private volatile int lookbackPeriod = DEFAULT_LOOKBACK_PERIOD;
    private volatile int volumePeriod = DEFAULT_VOLUME_PERIOD;

    // Historical data cache — avoids a Kite API call on every scheduler tick
    private volatile List<HistoricalDataResponse.HistoricalCandle> cachedCandles = null;
    private volatile LocalDateTime cacheTimestamp = null;
    private volatile String cachedInstrumentToken = null;
    private static final int CACHE_TTL_SECONDS = 60;

    @Autowired
    private LiquiditySweepRepository repository;

    @Autowired
    private InstrumentService instrumentService;


    // ============= MAIN ANALYSIS METHOD =============

    @Override
    public LiquiditySweepAnalysis analyzeLiquiditySweep(Integer appJobConfigNum) {
        if (appJobConfigNum != null) {
            MDC.put("appJobConfigNum", String.valueOf(appJobConfigNum));
        }

        try {
            logger.info("Starting Liquidity Sweep analysis for appJobConfigNum: {}", appJobConfigNum);

            List<HistoricalDataResponse.HistoricalCandle> candles = fetchHistoricalData(appJobConfigNum);
            if (candles.size() < lookbackPeriod) {
                logger.warn("Insufficient candle data for analysis. Need at least {} candles, got {}",
                        lookbackPeriod, candles.size());
                return null;
            }

            HistoricalDataResponse.HistoricalCandle currentCandle = candles.get(candles.size() - 1);

            LiquiditySweepAnalysis analysis = LiquiditySweepAnalysis.builder()
                    .appJobConfigNum(appJobConfigNum)
                    .analysisTimestamp(LocalDateTime.now(IST))
                    .open(currentCandle.getOpen())
                    .high(currentCandle.getHigh())
                    .low(currentCandle.getLow())
                    .close(currentCandle.getClose())
                    .spotPrice(currentCandle.getClose())   // alias kept for API compatibility
                    .volume(currentCandle.getVolume())
                    .whaleThreshold(whaleThreshold)
                    .lookbackPeriod(lookbackPeriod)
                    .volumePeriod(volumePeriod)
                    .timeframe(getTimeframe(appJobConfigNum))
                    .build();

            identifyLiquidityPools(analysis, candles);    // Layer 1: Market Structure
            detectWhaleActivity(analysis, candles);       // Layer 2: Whale Detection
            calculateTrendAndMomentum(analysis, candles); // Layer 3: Trend & Momentum
            detectLiquiditySweep(analysis, candles);      // Layer 4: Sweep Detection
            generateTradeSignal(analysis);                // Layer 5: Signal

            // Layer 6: Entry/Exit levels — only meaningful when a valid signal exists
            if (Boolean.TRUE.equals(analysis.getIsValidSetup())) {
                calculateEntryExitLevels(analysis, candles);
                analysis = repository.save(analysis);
            }

            logger.info("Liquidity Sweep analysis complete: Signal={}, Strength={}, Confidence={}%, WhaleType={}",
                    analysis.getSignalType(), analysis.getSignalStrength(),
                    analysis.getSignalConfidence(), analysis.getWhaleType());

            return analysis;

        } catch (Exception e) {
            logger.error("Error in liquidity sweep analysis: {}", e.getMessage(), e);
            return null;
        } finally {
            MDC.remove("appJobConfigNum");
        }
    }

    // ============= LAYER 1: MARKET STRUCTURE (Liquidity Pools) =============

    /**
     * Identify key pivot points where retail stop losses are likely clustered.
     * BSL (Buy Side Liquidity): Areas above swing highs
     * SSL (Sell Side Liquidity): Areas below swing lows
     */
    private void identifyLiquidityPools(LiquiditySweepAnalysis analysis,
                                         List<HistoricalDataResponse.HistoricalCandle> candles) {

        // Scan only the recent lookback window — avoids picking up stale levels from days ago
        int end   = candles.size() - 3; // i+2 must be valid → max i = size-3
        int start = Math.max(2, end - lookbackPeriod); // need at least prev1/prev2

        List<Double> swingHighs = new ArrayList<>();
        List<Double> swingLows  = new ArrayList<>();

        for (int i = start; i <= end; i++) {
            double high = candles.get(i).getHigh();
            double low  = candles.get(i).getLow();

            if (isSwingHigh(candles, i)) {
                swingHighs.add(high);
            } else if (isSwingLow(candles, i)) { // a candle cannot be both
                swingLows.add(low);
            }
        }

        // Reverse so index 0 = most recent (loop appends in chronological order)
        Collections.reverse(swingHighs);
        Collections.reverse(swingLows);

        setSwingLevels(analysis, swingHighs, swingLows);

        logger.debug("Identified {} swing highs and {} swing lows (lookback={})",
                swingHighs.size(), swingLows.size(), lookbackPeriod);
        logger.debug("BSL Levels: {}, {}, {}", analysis.getBslLevel1(), analysis.getBslLevel2(), analysis.getBslLevel3());
        logger.debug("SSL Levels: {}, {}, {}", analysis.getSslLevel1(), analysis.getSslLevel2(), analysis.getSslLevel3());
    }

    private boolean isSwingHigh(List<HistoricalDataResponse.HistoricalCandle> c, int i) {
        double h = c.get(i).getHigh();
        return h > c.get(i - 1).getHigh() && h > c.get(i - 2).getHigh()
            && h > c.get(i + 1).getHigh() && h > c.get(i + 2).getHigh();
    }

    private boolean isSwingLow(List<HistoricalDataResponse.HistoricalCandle> c, int i) {
        double l = c.get(i).getLow();
        return l < c.get(i - 1).getLow() && l < c.get(i - 2).getLow()
            && l < c.get(i + 1).getLow() && l < c.get(i + 2).getLow();
    }

    // ============= LAYER 2: QUANT ENGINE (Whale Detection) =============

    /**
     * Detect institutional whale activity using:
     * - Log-Normal Z-Score for volume anomaly detection
     * - Kaufman Efficiency Ratio for price movement quality
     */
    private void detectWhaleActivity(LiquiditySweepAnalysis analysis,
                                      List<HistoricalDataResponse.HistoricalCandle> candles) {

        // Use only the recent lookback window for volume baseline — same window as swing detection
        int lookbackStart = Math.max(0, candles.size() - lookbackPeriod);
        List<Long> volumes = candles.subList(lookbackStart, candles.size()).stream()
                .map(HistoricalDataResponse.HistoricalCandle::getVolume)
                .filter(Objects::nonNull)
                .toList();

        if (volumes.isEmpty()) {
            analysis.setHasWhaleActivity(false);
            analysis.setWhaleType("NONE");
            return;
        }

        double logVolumeZScore = calculateLogNormalZScore(volumes, analysis.getVolume());
        analysis.setLogVolumeZScore(logVolumeZScore);
        analysis.setIsVolumeAnomaly(logVolumeZScore > whaleThreshold);
        analysis.setAverageVolume((long) volumes.stream().mapToLong(Long::longValue).average().orElse(0));
        analysis.setVolumeStdDev(calculateStdDev(volumes.stream().map(Long::doubleValue).toList()));

        // KER — also stores price change and volatility as a side effect
        double ker = calculateKaufmanEfficiencyRatio(candles, lookbackPeriod);
        analysis.setKaufmanEfficiencyRatio(ker);
        if (candles.size() >= lookbackPeriod) {
            double startPrice = candles.get(candles.size() - lookbackPeriod).getClose();
            double endPrice   = candles.get(candles.size() - 1).getClose();
            analysis.setPriceChange(endPrice - startPrice);
            // volatility is already computed inside KER — reuse it via KER * direction
            analysis.setPriceVolatility(ker > 0 ? Math.abs(endPrice - startPrice) / ker : 0);
        }

        boolean hasVolumeAnomaly = Boolean.TRUE.equals(analysis.getIsVolumeAnomaly());

        // Default both flags to false; set to true only for the matching type
        analysis.setIsAbsorption(false);
        analysis.setIsPropulsion(false);

        if (hasVolumeAnomaly) {
            analysis.setHasWhaleActivity(true);
            if (ker < KER_ABSORPTION_THRESHOLD) {
                analysis.setWhaleType("ABSORPTION");
                analysis.setIsAbsorption(true);
                logger.info("Whale Activity: ABSORPTION (Iceberg) - Z-Score: {}, KER: {}",
                        String.format("%.2f", logVolumeZScore), String.format("%.2f", ker));
            } else if (ker > KER_PROPULSION_THRESHOLD) {
                analysis.setWhaleType("PROPULSION");
                analysis.setIsPropulsion(true);
                logger.info("Whale Activity: PROPULSION (Drive) - Z-Score: {}, KER: {}",
                        String.format("%.2f", logVolumeZScore), String.format("%.2f", ker));
            } else {
                analysis.setWhaleType("ACCUMULATION");
                logger.info("Whale Activity: ACCUMULATION - Z-Score: {}, KER: {}",
                        String.format("%.2f", logVolumeZScore), String.format("%.2f", ker));
            }
        } else {
            analysis.setWhaleType("NONE");
            analysis.setHasWhaleActivity(false);
        }
    }

    /**
     * Calculate Log-Normal Z-Score for volume anomaly detection.
     * This normalizes volume data to detect statistically significant outliers.
     */
    private double calculateLogNormalZScore(List<Long> volumes, Long currentVolume) {
        if (volumes.isEmpty() || currentVolume == null || currentVolume <= 0) {
            return 0.0;
        }

        double[] logVols = volumes.stream()
                .filter(v -> v > 0)
                .mapToDouble(v -> Math.log(v.doubleValue()))
                .toArray();

        if (logVols.length < 2) return 0.0;

        // Single-pass mean
        double mean = 0;
        for (double v : logVols) mean += v;
        mean /= logVols.length;

        // Single-pass variance (sample variance — Bessel's correction for small windows)
        double variance = 0;
        for (double v : logVols) variance += (v - mean) * (v - mean);
        variance /= (logVols.length - 1);

        double stdDev = Math.sqrt(variance);
        if (stdDev == 0) return 0.0;

        return (Math.log(currentVolume.doubleValue()) - mean) / stdDev;
    }

    /**
     * Calculate Kaufman Efficiency Ratio (KER).
     * KER = Direction / Volatility
     * - High KER (close to 1): Price moved efficiently in one direction
     * - Low KER (close to 0): Price moved erratically with high volatility
     */
    private double calculateKaufmanEfficiencyRatio(List<HistoricalDataResponse.HistoricalCandle> candles, int period) {
        int n = candles.size();
        if (n < period) {
            return 0.5; // Neutral if insufficient data
        }

        int startIndex = n - period;

        double direction  = Math.abs(candles.get(n - 1).getClose() - candles.get(startIndex).getClose());
        double volatility = 0;
        for (int i = startIndex + 1; i < n; i++) {
            volatility += Math.abs(candles.get(i).getClose() - candles.get(i - 1).getClose());
        }

        return volatility == 0 ? 1.0 : direction / volatility;
    }

    // ============= LAYER 3: TREND & MOMENTUM FILTERS =============

    /**
     * Calculate trend (EMA {@value #DEFAULT_EMA_PERIOD}) and momentum (RSI) indicators.
     */
    private void calculateTrendAndMomentum(LiquiditySweepAnalysis analysis,
                                            List<HistoricalDataResponse.HistoricalCandle> candles) {

        double close = analysis.getClose();

        // EMA converges after ~4× period candles — trim like RSI to avoid iterating all 450
        int emaWindowSize = DEFAULT_EMA_PERIOD * 4;
        List<HistoricalDataResponse.HistoricalCandle> emaCandles = candles.size() > emaWindowSize
                ? candles.subList(candles.size() - emaWindowSize, candles.size())
                : candles;

        double ema = calculateEMA(emaCandles, DEFAULT_EMA_PERIOD);
        analysis.setEma200(ema);
        analysis.setIsAboveEma200(close > ema);

        if      (close > ema * 1.002) analysis.setTrendDirection("BULLISH");  // 0.2% buffer
        else if (close < ema * 0.998) analysis.setTrendDirection("BEARISH");
        else                          analysis.setTrendDirection("NEUTRAL");

        // RSI converges after ~4× period candles — no need to pass all 450
        int rsiWindowSize = DEFAULT_RSI_PERIOD * 4;
        List<HistoricalDataResponse.HistoricalCandle> rsiCandles = candles.size() > rsiWindowSize
                ? candles.subList(candles.size() - rsiWindowSize, candles.size())
                : candles;

        double rsi = calculateRSI(rsiCandles, DEFAULT_RSI_PERIOD);
        analysis.setRsiValue(rsi);
        analysis.setIsRsiOversold(rsi < RSI_OVERSOLD);
        analysis.setIsRsiOverbought(rsi > RSI_OVERBOUGHT);

        logger.debug("Trend: {} | EMA{}: {} | RSI: {}",
                analysis.getTrendDirection(), DEFAULT_EMA_PERIOD,
                String.format("%.2f", ema), String.format("%.2f", rsi));
    }

    /**
     * Calculate Exponential Moving Average
     */
    private double calculateEMA(List<HistoricalDataResponse.HistoricalCandle> candles, int period) {
        int n = candles.size();
        if (n < period) {
            return candles.stream()
                    .mapToDouble(HistoricalDataResponse.HistoricalCandle::getClose)
                    .average()
                    .orElse(0);
        }

        // Seed: SMA of first {period} candles
        double ema = 0;
        for (int i = 0; i < period; i++) ema += candles.get(i).getClose();
        ema /= period;

        double multiplier = 2.0 / (period + 1);
        for (int i = period; i < n; i++) {
            ema = (candles.get(i).getClose() - ema) * multiplier + ema;
        }

        return ema;
    }

    /**
     * Calculate Relative Strength Index using Wilder's smoothed method (matches TradingView).
     * Seed: SMA of first {period} gains/losses. Then: avgGain = (prevAvgGain*(p-1) + gain) / p.
     */
    private double calculateRSI(List<HistoricalDataResponse.HistoricalCandle> candles, int period) {
        int n = candles.size();
        if (n < period + 1) {
            return 50.0;
        }

        // Seed: average gain/loss over first {period} changes
        double avgGain = 0;
        double avgLoss = 0;
        for (int i = 1; i <= period; i++) {
            double change = candles.get(i).getClose() - candles.get(i - 1).getClose();
            if (change > 0) avgGain += change;
            else            avgLoss -= change; // change < 0, so -change is positive
        }
        avgGain /= period;
        avgLoss /= period;

        // Wilder's smoothing for remaining candles
        for (int i = period + 1; i < n; i++) {
            double change = candles.get(i).getClose() - candles.get(i - 1).getClose();
            double gain   = change > 0 ?  change : 0.0;
            double loss   = change < 0 ? -change : 0.0;
            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
        }

        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    // ============= LAYER 4: LIQUIDITY SWEEP DETECTION =============

    /**
     * Detect if price has swept a liquidity level.
     * Checks all 3 BSL/SSL levels and supports two-candle patterns:
     * - Same candle: wick breaks level AND close back within range on same bar
     * - Two candle: previous candle wicked through level, current candle closes back inside
     */
    private void detectLiquiditySweep(LiquiditySweepAnalysis analysis,
                                       List<HistoricalDataResponse.HistoricalCandle> candles) {

        HistoricalDataResponse.HistoricalCandle curr = candles.get(candles.size() - 1);
        HistoricalDataResponse.HistoricalCandle prev = candles.get(candles.size() - 2);

        List<Double> bslLevels = nonNullLevels(analysis.getBslLevel1(), analysis.getBslLevel2(), analysis.getBslLevel3());
        List<Double> sslLevels = nonNullLevels(analysis.getSslLevel1(), analysis.getSslLevel2(), analysis.getSslLevel3());

        // BSL sweep: current or prev wick above level, current close back below
        Double bslHit = findSweptLevel(bslLevels, curr.getHigh(), prev.getHigh(), curr.getClose(), true);
        // SSL sweep: current or prev wick below level, current close back above
        Double sslHit = bslHit == null
                ? findSweptLevel(sslLevels, curr.getLow(), prev.getLow(), curr.getClose(), false)
                : null;

        boolean bslSwept  = bslHit != null;
        boolean sslSwept  = sslHit != null;
        boolean closedBack = bslSwept || sslSwept;

        analysis.setBslSwept(bslSwept);
        analysis.setSslSwept(sslSwept);
        analysis.setSweptLevel(bslSwept ? bslHit : sslHit);
        analysis.setSweepType(bslSwept ? "BSL_SWEEP" : sslSwept ? "SSL_SWEEP" : "NONE");
        analysis.setPriceClosedBack(closedBack);
        analysis.setHasInstitutionalConfirmation(closedBack && Boolean.TRUE.equals(analysis.getHasWhaleActivity()));
        analysis.setIsTrendAligned(
                (sslSwept && "BULLISH".equals(analysis.getTrendDirection())) ||
                (bslSwept && "BEARISH".equals(analysis.getTrendDirection())));
        analysis.setIsMomentumAligned(
                (sslSwept && Boolean.TRUE.equals(analysis.getIsRsiOversold())) ||
                (bslSwept && Boolean.TRUE.equals(analysis.getIsRsiOverbought())));
    }

    /**
     * Finds the first level in the list where either the current or previous candle's wick
     * broke through and the current candle closed back on the other side.
     *
     * @param levels      candidate levels (most recent first)
     * @param currWick    current candle's high (BSL) or low (SSL)
     * @param prevWick    previous candle's high (BSL) or low (SSL)
     * @param currClose   current candle close
     * @param isBsl       true = BSL (wick above, close below); false = SSL (wick below, close above)
     * @return matched level, or null if no sweep found
     */
    private Double findSweptLevel(List<Double> levels, double currWick, double prevWick,
                                   double currClose, boolean isBsl) {
        String sweepLabel = isBsl ? "BSL" : "SSL";
        for (Double level : levels) {
            boolean wickBroke  = isBsl ? (currWick > level || prevWick > level)
                                       : (currWick < level || prevWick < level);
            boolean closedBack = isBsl ? currClose < level : currClose > level;
            if (wickBroke && closedBack) {
                boolean sameCandleSweep = isBsl ? currWick > level : currWick < level;
                logger.info("{} SWEEP detected at level {} ({})", sweepLabel, level,
                        sameCandleSweep ? "same-candle" : "two-candle");
                return level;
            }
        }
        return null;
    }

    // ============= LAYER 5: TRADE SIGNAL GENERATION =============

    /**
     * Generate trade signal based on all analysis layers.
     * A valid signal requires:
     * - Price swept a liquidity level
     * - Price closed back within range
     * - Institutional activity confirmed
     * - Trend and momentum filters aligned (optional for strength)
     */
    private void generateTradeSignal(LiquiditySweepAnalysis analysis) {
        if (!Boolean.TRUE.equals(analysis.getPriceClosedBack())) {
            analysis.setSignalType("NONE");
            analysis.setSignalStrength("WEAK");
            analysis.setSignalConfidence(0.0);
            analysis.setIsValidSetup(false);
            return;
        }

        String signalType  = Boolean.TRUE.equals(analysis.getSslSwept()) ? "BUY" : "SELL";
        double confidence  = buildConfidence(analysis);
        String strength    = confidence >= 80 ? "STRONG" : confidence >= 60 ? "MODERATE" : "WEAK";
        boolean validSetup = confidence >= 60;

        analysis.setSignalType(signalType);
        analysis.setSignalStrength(strength);
        analysis.setSignalConfidence(confidence);
        analysis.setIsValidSetup(validSetup);

        if (validSetup) {
            logger.info("VALID {} SETUP — Strength: {}, Confidence: {}%", signalType, strength, (int) confidence);
        } else {
            logger.debug("Sweep detected ({}) but confidence {}% below threshold — no signal",
                    analysis.getSweepType(), (int) confidence);
        }
    }

    /** Confidence scoring — identical for BUY and SELL setups. */
    private double buildConfidence(LiquiditySweepAnalysis analysis) {
        double confidence = 40; // base: sweep + close-back confirmed
        if (Boolean.TRUE.equals(analysis.getHasInstitutionalConfirmation())) confidence += 25;
        if (Boolean.TRUE.equals(analysis.getIsTrendAligned()))               confidence += 15;
        if (Boolean.TRUE.equals(analysis.getIsMomentumAligned()))            confidence += 10;
        if ("ABSORPTION".equals(analysis.getWhaleType()))                    confidence += 10;
        return confidence;
    }

    // ============= LAYER 6: ENTRY/EXIT CALCULATION =============

    /**
     * Calculate entry, stop loss, and take profit levels using ATR.
     */
    private void calculateEntryExitLevels(LiquiditySweepAnalysis analysis,
                                           List<HistoricalDataResponse.HistoricalCandle> candles) {

        double atr        = calculateATR(candles, DEFAULT_ATR_PERIOD);
        double entryPrice = analysis.getClose();
        analysis.setAtrValue(atr);
        analysis.setEntryPrice(entryPrice);

        boolean isBuy     = "BUY".equals(analysis.getSignalType());
        double  direction = isBuy ? 1.0 : -1.0;

        // SL: just beyond swept level (0.5 ATR buffer); fallback uses DEFAULT_ATR_MULTIPLIER
        double stopLoss = analysis.getSweptLevel() != null
                ? analysis.getSweptLevel() - direction * (atr * 0.5)
                : entryPrice          - direction * (atr * DEFAULT_ATR_MULTIPLIER);

        double riskPoints = Math.abs(entryPrice - stopLoss);

        analysis.setStopLossPrice(stopLoss);
        analysis.setRiskPoints(riskPoints);
        analysis.setTakeProfit1(entryPrice + direction * riskPoints);
        analysis.setTakeProfit2(entryPrice + direction * riskPoints * 2);
        analysis.setTakeProfit3(entryPrice + direction * riskPoints * 3);
        analysis.setRiskRewardRatio(DEFAULT_RISK_REWARD);
        analysis.setSuggestedStrike(Math.round(entryPrice / 50) * 50.0);
        analysis.setSuggestedOptionType(isBuy ? "CE" : "PE");
        analysis.setOptionStrategy(isBuy ? "BUY_CE" : "BUY_PE");
    }

    /**
     * Calculate Average True Range (ATR)
     */
    private double calculateATR(List<HistoricalDataResponse.HistoricalCandle> candles, int period) {
        if (candles.size() < period + 1) {
            return 0;
        }

        List<Double> trueRanges = new ArrayList<>();

        for (int i = 1; i < candles.size(); i++) {
            HistoricalDataResponse.HistoricalCandle current = candles.get(i);
            HistoricalDataResponse.HistoricalCandle previous = candles.get(i - 1);

            double highLow = current.getHigh() - current.getLow();
            double highClose = Math.abs(current.getHigh() - previous.getClose());
            double lowClose = Math.abs(current.getLow() - previous.getClose());

            double trueRange = Math.max(highLow, Math.max(highClose, lowClose));
            trueRanges.add(trueRange);
        }

        // Calculate ATR as average of last 'period' true ranges
        int startIdx = Math.max(0, trueRanges.size() - period);
        return trueRanges.subList(startIdx, trueRanges.size()).stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0);
    }

    // ============= HELPER METHODS =============

    private List<HistoricalDataResponse.HistoricalCandle> fetchHistoricalData(Integer appJobConfigNum) {
        try {
            String instrumentToken = getInstrumentToken(appJobConfigNum);

            // Return cached data if still fresh and for the same instrument
            if (cachedCandles != null && cacheTimestamp != null
                    && instrumentToken.equals(cachedInstrumentToken)
                    && cacheTimestamp.plusSeconds(CACHE_TTL_SECONDS).isAfter(LocalDateTime.now(IST))) {
                logger.debug("fetchHistoricalData: returning cached candles (age < {}s)", CACHE_TTL_SECONDS);
                return cachedCandles;
            }

            LocalDateTime now = LocalDateTime.now(IST);
            HistoricalDataRequest request = new HistoricalDataRequest();
            request.setInstrumentToken(instrumentToken);
            request.setInterval("15minute");
            request.setFromDate(now.minusDays(5));
            request.setToDate(now);
            request.setContinuous(false);
            request.setOi(false);

            HistoricalDataResponse response = instrumentService.getHistoricalData(request);

            if (response != null && response.isSuccess() && response.getCandles() != null) {
                logger.debug("Fetched {} candles for liquidity sweep analysis", response.getCandles().size());
                cachedCandles          = response.getCandles();
                cacheTimestamp         = LocalDateTime.now(IST);
                cachedInstrumentToken  = instrumentToken;
                return cachedCandles;
            }

            return new ArrayList<>();

        } catch (Exception e) {
            logger.error("Error fetching historical data: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private String getInstrumentToken(Integer appJobConfigNum) {
        if (appJobConfigNum != null) {
            var instruments = instrumentService.getInstrumentsFromAppJobConfigNum(appJobConfigNum);
            if (!instruments.isEmpty()) {
                return String.valueOf(instruments.get(0).getInstrument().getInstrument_token());
            }
            logger.warn("No instruments found for appJobConfigNum: {}, defaulting to NIFTY", appJobConfigNum);
        }
        return String.valueOf(NIFTY_INSTRUMENT_TOKEN);
    }

    private String getTimeframe(Integer appJobConfigNum) {
        // TODO: derive timeframe from appJobConfigNum job type config
        logger.debug("getTimeframe: appJobConfigNum={} not yet mapped, defaulting to 15m", appJobConfigNum);
        return "15m";
    }

    /** Returns a list of non-null values from up to three level inputs, preserving order. */
    private List<Double> nonNullLevels(Double l1, Double l2, Double l3) {
        List<Double> levels = new ArrayList<>(3);
        if (l1 != null) levels.add(l1);
        if (l2 != null) levels.add(l2);
        if (l3 != null) levels.add(l3);
        return levels;
    }

    /** Sets all BSL, SSL, and swing-point fields on the analysis from the two sorted lists. */
    private void setSwingLevels(LiquiditySweepAnalysis analysis,
                                 List<Double> swingHighs, List<Double> swingLows) {
        if (swingHighs.size() >= 1) { analysis.setBslLevel1(swingHighs.get(0)); analysis.setSwingHigh1(swingHighs.get(0)); }
        if (swingHighs.size() >= 2) { analysis.setBslLevel2(swingHighs.get(1)); analysis.setSwingHigh2(swingHighs.get(1)); }
        if (swingHighs.size() >= 3) { analysis.setBslLevel3(swingHighs.get(2)); analysis.setSwingHigh3(swingHighs.get(2)); }
        if (swingLows.size()  >= 1) { analysis.setSslLevel1(swingLows.get(0));  analysis.setSwingLow1(swingLows.get(0));  }
        if (swingLows.size()  >= 2) { analysis.setSslLevel2(swingLows.get(1));  analysis.setSwingLow2(swingLows.get(1));  }
        if (swingLows.size()  >= 3) { analysis.setSslLevel3(swingLows.get(2));  analysis.setSwingLow3(swingLows.get(2));  }
    }

    private double calculateStdDev(List<Double> values) {
        int n = values.size();
        if (n < 2) return 0;
        double mean = 0;
        for (double v : values) mean += v;
        mean /= n;
        double variance = 0;
        for (double v : values) variance += (v - mean) * (v - mean);
        return Math.sqrt(variance / (n - 1)); // sample std dev (Bessel's correction)
    }

    // ============= SERVICE INTERFACE IMPLEMENTATIONS =============

    @Override
    public Optional<LiquiditySweepAnalysis> getLatestAnalysis(Integer appJobConfigNum) {
        return repository.findLatestByAppJobConfigNum(appJobConfigNum);
    }

    @Override
    public Optional<LiquiditySweepAnalysis> getLatestValidSetup(Integer appJobConfigNum) {
        return repository.findLatestValidSetupByAppJobConfigNum(appJobConfigNum);
    }

    @Override
    public Map<String, Object> checkLiquiditySweepSignal(Integer appJobConfigNum) {
        Map<String, Object> result = new HashMap<>();
        result.put("hasSignal", false);
        result.put("signalSource", "LIQUIDITY_SWEEP");

        try {
            // Run fresh analysis
            LiquiditySweepAnalysis analysis = analyzeLiquiditySweep(appJobConfigNum);

            if (analysis != null && Boolean.TRUE.equals(analysis.getIsValidSetup())) {
                result.put("hasSignal", true);
                result.put("signalType", analysis.getSignalType());
                result.put("signalStrength", analysis.getSignalStrength());
                result.put("confidence", analysis.getSignalConfidence());
                result.put("whaleType", analysis.getWhaleType());
                result.put("sweepType", analysis.getSweepType());
                result.put("sweptLevel", analysis.getSweptLevel());
                result.put("entryPrice", analysis.getEntryPrice());
                result.put("stopLoss", analysis.getStopLossPrice());
                result.put("takeProfit", analysis.getTakeProfit2());
                result.put("suggestedOption", analysis.getSuggestedOptionType());
                result.put("analysisId", analysis.getId());
            } else if (analysis != null) {
                result.put("reason", "No valid liquidity sweep setup detected");
                result.put("whaleActivity", analysis.getHasWhaleActivity());
                result.put("sweepType", analysis.getSweepType());
            }

        } catch (Exception e) {
            logger.error("Error checking liquidity sweep signal: {}", e.getMessage());
            result.put("error", e.getMessage());
        }

        return result;
    }

    @Override
    public Map<String, Object> getTradeRecommendation(Integer appJobConfigNum) {
        Map<String, Object> recommendation = new HashMap<>();

        Optional<LiquiditySweepAnalysis> latestSetup = getLatestValidSetup(appJobConfigNum);

        if (latestSetup.isPresent()) {
            LiquiditySweepAnalysis analysis = latestSetup.get();
            recommendation.put("hasRecommendation", true);
            recommendation.put("signal", analysis.getSignalType());
            recommendation.put("strength", analysis.getSignalStrength());
            recommendation.put("confidence", analysis.getSignalConfidence());
            recommendation.put("whaleType", analysis.getWhaleType());
            recommendation.put("entry", analysis.getEntryPrice());
            recommendation.put("stopLoss", analysis.getStopLossPrice());
            recommendation.put("takeProfit1", analysis.getTakeProfit1());
            recommendation.put("takeProfit2", analysis.getTakeProfit2());
            recommendation.put("takeProfit3", analysis.getTakeProfit3());
            recommendation.put("riskReward", analysis.getRiskRewardRatio());
            recommendation.put("optionType", analysis.getSuggestedOptionType());
            recommendation.put("optionStrategy", analysis.getOptionStrategy());
            recommendation.put("analysisTime", analysis.getAnalysisTimestamp());
        } else {
            recommendation.put("hasRecommendation", false);
            recommendation.put("message", "No valid liquidity sweep setup available");
        }

        return recommendation;
    }

    @Override
    public boolean doesLiquiditySweepSupportTrade(String tradeDirection, Integer appJobConfigNum) {
        Optional<LiquiditySweepAnalysis> latest = getLatestAnalysis(appJobConfigNum);

        if (latest.isEmpty()) {
            return true; // Allow if no analysis available
        }

        LiquiditySweepAnalysis analysis = latest.get();
        boolean bslSwept = Boolean.TRUE.equals(analysis.getBslSwept());
        boolean sslSwept = Boolean.TRUE.equals(analysis.getSslSwept());

        if ("BUY".equals(tradeDirection))  return !bslSwept || sslSwept;
        if ("SELL".equals(tradeDirection)) return !sslSwept || bslSwept;
        return true;
    }

    @Override
    public Map<String, Object> getLiquidityLevels(Integer appJobConfigNum) {
        Map<String, Object> levels = new HashMap<>();

        Optional<LiquiditySweepAnalysis> latest = getLatestAnalysis(appJobConfigNum);
        if (latest.isEmpty()) return levels;

        LiquiditySweepAnalysis analysis = latest.get();

        levels.put("buySideLiquidity", buildLevelMap("BSL",
                analysis.getBslLevel1(), analysis.getBslLevel2(), analysis.getBslLevel3()));
        levels.put("sellSideLiquidity", buildLevelMap("SSL",
                analysis.getSslLevel1(), analysis.getSslLevel2(), analysis.getSslLevel3()));
        levels.put("currentPrice", analysis.getSpotPrice());
        levels.put("analysisTime", analysis.getAnalysisTimestamp());

        return levels;
    }

    private Map<String, Double> buildLevelMap(String prefix, Double l1, Double l2, Double l3) {
        List<Double> vals = nonNullLevels(l1, l2, l3);
        Map<String, Double> map = new LinkedHashMap<>(vals.size());
        for (int i = 0; i < vals.size(); i++) map.put(prefix + (i + 1), vals.get(i));
        return map;
    }

    @Override
    public Map<String, Object> getWhaleActivityIndicators(Integer appJobConfigNum) {
        Map<String, Object> indicators = new HashMap<>();

        Optional<LiquiditySweepAnalysis> latest = getLatestAnalysis(appJobConfigNum);
        if (latest.isEmpty()) return indicators;

        LiquiditySweepAnalysis analysis = latest.get();
        indicators.put("hasWhaleActivity",   analysis.getHasWhaleActivity());
        indicators.put("whaleType",          analysis.getWhaleType());
        indicators.put("volumeZScore",       analysis.getLogVolumeZScore());
        indicators.put("kaufmanEfficiency",  analysis.getKaufmanEfficiencyRatio());
        indicators.put("isAbsorption",       analysis.getIsAbsorption());
        indicators.put("isPropulsion",       analysis.getIsPropulsion());
        indicators.put("averageVolume",      analysis.getAverageVolume());
        indicators.put("currentVolume",      analysis.getVolume());
        indicators.put("whaleThreshold",     analysis.getWhaleThreshold());

        return indicators;
    }

    @Override
    public List<LiquiditySweepAnalysis> getTodaysAnalyses(Integer appJobConfigNum) {
        LocalDateTime startOfDay = LocalDate.now(IST).atStartOfDay();
        return repository.findTodaysAnalyses(appJobConfigNum, startOfDay);
    }

    @Override
    public void setWhaleThreshold(double sigma) {
        this.whaleThreshold = sigma;
        logger.info("Whale threshold updated to {} sigma", sigma);
    }

    @Override
    public void setLookbackPeriod(int periods) {
        this.lookbackPeriod = periods;
        logger.info("Lookback period updated to {} periods", periods);
    }

    @Override
    public Map<String, Object> getConfiguration() {
        Map<String, Object> config = new HashMap<>();
        config.put("whaleThreshold", whaleThreshold);
        config.put("lookbackPeriod", lookbackPeriod);
        config.put("volumePeriod", volumePeriod);
        config.put("emaPeriod", DEFAULT_EMA_PERIOD);
        config.put("rsiPeriod", DEFAULT_RSI_PERIOD);
        config.put("atrMultiplier", DEFAULT_ATR_MULTIPLIER);
        config.put("defaultRiskReward", DEFAULT_RISK_REWARD);
        config.put("kerAbsorptionThreshold", KER_ABSORPTION_THRESHOLD);
        config.put("kerPropulsionThreshold", KER_PROPULSION_THRESHOLD);
        return config;
    }
}

