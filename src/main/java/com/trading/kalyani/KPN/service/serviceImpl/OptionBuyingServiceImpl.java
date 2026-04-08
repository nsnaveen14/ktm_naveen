package com.trading.kalyani.KPN.service.serviceImpl;

import com.trading.kalyani.KPN.dto.brahmastra.LiveScanResult;
import com.trading.kalyani.KPN.entity.InternalOrderBlock;
import com.trading.kalyani.KPN.entity.OptionBuyingConfig;
import com.trading.kalyani.KPN.entity.SimulatedTrade;
import com.trading.kalyani.KPN.repository.OptionBuyingConfigRepository;
import com.trading.kalyani.KPN.repository.SimulatedTradeRepository;
import com.trading.kalyani.KPN.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.trading.kalyani.KPN.constants.ApplicationConstants.*;

/**
 * Option Buying Strategy — unified ATM CE/PE buyer driven by
 * IOB, Brahmastra and ZeroHero signals.
 *
 * Optimizations applied:
 *  1. Signal Confluence — only trade when N sources agree on the same direction.
 *  2. VIX / Premium Filter — skip entry when VIX too high or premium out of range.
 *  3. Trailing Stop Loss — ratchet the SL up as the option gains value.
 */
@Service
public class OptionBuyingServiceImpl implements OptionBuyingService {

    private static final Logger logger = LoggerFactory.getLogger(OptionBuyingServiceImpl.class);

    // ── Signal source tags ────────────────────────────────────────────────────
    static final String SRC_IOB        = "OPT_BUY_IOB";
    static final String SRC_BRAHMASTRA = "OPT_BUY_BRAHMASTRA";
    static final String SRC_ZERO_HERO  = "OPT_BUY_ZERO_HERO";
    private static final String OPT_BUY_PREFIX = "OPT_BUY";

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final int    NIFTY_LOT_SIZE      = 65;
    private static final int    SIGNAL_COOLDOWN_MIN = 5;
    private static final double BRAHMASTRA_MIN_CONF = 70.0;
    private static final ZoneId IST                 = ZoneId.of("Asia/Kolkata");
    private static final LocalTime HARD_EXIT_TIME   = LocalTime.of(15, 25);

    // ── Internal signal candidate record ─────────────────────────────────────
    private record SignalCandidate(String source, String direction, String strength) {}

    // ── Cooldown map: (source + "_" + direction) → last fired time ────────────
    private final Map<String, LocalDateTime> cooldowns = new ConcurrentHashMap<>();

    // ── Dependencies ──────────────────────────────────────────────────────────
    @Autowired
    private OptionBuyingConfigRepository configRepository;

    @Autowired
    private SimulatedTradeRepository tradeRepository;

    @Lazy
    @Autowired
    private CandlePredictionService candlePredictionService;

    @Lazy
    @Autowired(required = false)
    private BrahmastraService brahmastraService;

    @Autowired(required = false)
    private ZeroHeroSignalService zeroHeroSignalService;

    @Lazy
    @Autowired(required = false)
    private InternalOrderBlockService internalOrderBlockService;

    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    @Autowired(required = false)
    private TelegramNotificationService telegramNotificationService;

    // Alert cooldown: tradeId → sent flag (prevents duplicate alerts on re-entry)
    private final Map<String, Boolean> telegramAlertSent = new ConcurrentHashMap<>();

    // ═════════════════════════════════════════════════════════════════════════
    // Config CRUD
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public OptionBuyingConfig getConfig() {
        return configRepository.findById(1L).orElseGet(() -> {
            OptionBuyingConfig d = new OptionBuyingConfig(
                    1L, false, true, true, true, true,
                    2, 30.0, 15.0, 3, -5000.0, 10,
                    "09:20", "15:00", "MODERATE", true,
                    // confluence
                    false, 2,
                    // vix/premium
                    20.0, 10.0, 300.0,
                    // trailing SL
                    false, 50.0, 50.0,
                    // zero hero min score
                    2);
            return configRepository.save(d);
        });
    }

    @Override
    public OptionBuyingConfig updateConfig(OptionBuyingConfig config) {
        config.setId(1L);
        return configRepository.save(config);
    }

    @Override
    public void enable() {
        OptionBuyingConfig cfg = getConfig();
        cfg.setEnabled(true);
        configRepository.save(cfg);
        logger.info("Option Buying Strategy ENABLED");
    }

    @Override
    public void disable() {
        OptionBuyingConfig cfg = getConfig();
        cfg.setEnabled(false);
        configRepository.save(cfg);
        logger.info("Option Buying Strategy DISABLED");
    }

    @Override
    public Map<String, Object> getStatus() {
        OptionBuyingConfig cfg = getConfig();
        List<SimulatedTrade> open  = getOpenTrades();
        List<SimulatedTrade> today = getTodayTrades();
        double todayPnl = today.stream()
                .filter(t -> TRADE_STATUS_CLOSED.equals(t.getStatus()) && t.getNetPnl() != null)
                .mapToDouble(SimulatedTrade::getNetPnl).sum();
        Map<String, Object> status = new HashMap<>();
        status.put("enabled",         cfg.isEnabled());
        status.put("openTradesCount", open.size());
        status.put("todayTrades",     today.size());
        status.put("todayPnl",        todayPnl);
        return status;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Core engine
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public void checkAndExecute() {
        OptionBuyingConfig config = getConfig();
        if (!config.isEnabled()) return;

        // ── Time window ───────────────────────────────────────────────────────
        LocalTime now = LocalTime.now(IST);
        try {
            LocalTime start = LocalTime.parse(config.getTradeStartTime());
            LocalTime end   = LocalTime.parse(config.getTradeEndTime());
            if (now.isBefore(start) || now.isAfter(end)) return;
        } catch (Exception e) {
            logger.warn("OPT-BUY: invalid time window config: {}", e.getMessage());
            return;
        }

        // ── Live data ─────────────────────────────────────────────────────────
        Map<String, Object> liveData = candlePredictionService.getLiveTickData();
        if (liveData == null) return;

        // ── OPTIMIZATION 2: VIX gate (global — applies before any trade) ─────
        double maxVix = config.getMaxVix() != null ? config.getMaxVix() : 0.0;
        if (maxVix > 0) {
            Double vix = toDouble(liveData, "vixValue");
            if (vix != null && vix > maxVix) {
                logger.info("OPT-BUY: VIX {} > {} - skipping all entries", String.format("%.1f", vix), maxVix);
                return;
            }
        }

        // ── Risk gates ────────────────────────────────────────────────────────
        List<SimulatedTrade> open = getOpenTrades();
        if (open.size() >= config.getMaxOpenTrades()) return;

        List<SimulatedTrade> today = getTodayTrades();
        if (today.size() >= config.getMaxDailyTrades()) return;

        double todayPnl = today.stream()
                .filter(t -> TRADE_STATUS_CLOSED.equals(t.getStatus()) && t.getNetPnl() != null)
                .mapToDouble(SimulatedTrade::getNetPnl).sum();
        if (todayPnl <= config.getMaxDailyLoss()) {
            logger.warn("OPT-BUY: daily loss limit hit ({} <= {})", todayPnl, config.getMaxDailyLoss());
            return;
        }

        // ── Collect confluence candidates (IOB + Brahmastra + GainzAlgo) ──────
        List<SignalCandidate> candidates = new ArrayList<>();
        if (config.isEnableBrahmastra()) collectBrahmastra(candidates, config);
        if (config.isEnableIob())        collectIob(candidates, liveData, config);

        // ── OPTIMIZATION 1: Confluence filter ────────────────────────────────
        if (!candidates.isEmpty()) {
            processWithConfluence(candidates, liveData, config);
        }

        // ── ZeroHero: independent (its own strike/premium logic) ──────────────
        if (config.isEnableZeroHero()) checkZeroHero(liveData, config);
    }

    // ─── Signal collectors (return candidates, don't place trades) ───────────

    private void collectBrahmastra(List<SignalCandidate> candidates, OptionBuyingConfig config) {
        if (brahmastraService == null) return;
        try {
            LiveScanResult scan = brahmastraService.scanSymbol("NIFTY", "5min");
            if (scan == null) return;

            String direction = scan.getSignalType();
            if (direction == null || "NO_SIGNAL".equals(direction)) return;

            Double confidence = scan.getConfidenceScore();
            if (confidence == null || confidence < BRAHMASTRA_MIN_CONF) return;

            String strength = confidence >= 85.0 ? "STRONG" : "MODERATE";
            if (!isStrengthSufficient(strength, config.getMinSignalStrength())) return;

            candidates.add(new SignalCandidate(SRC_BRAHMASTRA, direction, strength));
            logger.debug("OPT-BUY: Brahmastra candidate {} ({}) conf={}%", direction, strength, confidence);
        } catch (Exception e) {
            logger.error("OPT-BUY: Brahmastra collect error: {}", e.getMessage());
        }
    }

    private void collectIob(List<SignalCandidate> candidates,
                            Map<String, Object> liveData, OptionBuyingConfig config) {
        if (internalOrderBlockService == null) return;
        try {
            if (LocalTime.now(IST).isBefore(LocalTime.of(9, 45))) return;

            Double niftyLTP = toDouble(liveData, "niftyLTP");
            if (niftyLTP == null) return;

            List<InternalOrderBlock> freshIOBs =
                    internalOrderBlockService.getFreshIOBs(NIFTY_INSTRUMENT_TOKEN);
            if (freshIOBs == null || freshIOBs.isEmpty()) return;

            for (InternalOrderBlock iob : freshIOBs) {
                if (iob.getZoneLow() == null || iob.getZoneHigh() == null) continue;
                // Price must be strictly inside the zone — entry only when price re-enters
                boolean near = niftyLTP >= iob.getZoneLow() && niftyLTP <= iob.getZoneHigh();
                if (!near) continue;

                String direction;
                if ("BULLISH_IOB".equals(iob.getObType()))       direction = "BUY";
                else if ("BEARISH_IOB".equals(iob.getObType()))  direction = "SELL";
                else continue; // skip unknown/invalid types

                candidates.add(new SignalCandidate(SRC_IOB, direction, "MODERATE"));
                logger.debug("OPT-BUY: IOB candidate {} ({})", direction, iob.getObType());
                break; // one IOB candidate per cycle
            }
        } catch (Exception e) {
            logger.error("OPT-BUY: IOB collect error: {}", e.getMessage());
        }
    }

    // ─── Confluence decision + trade placement ────────────────────────────────

    private void processWithConfluence(List<SignalCandidate> candidates,
                                       Map<String, Object> liveData,
                                       OptionBuyingConfig config) {
        // Group by direction
        Map<String, List<SignalCandidate>> byDirection = candidates.stream()
                .collect(Collectors.groupingBy(SignalCandidate::direction));

        String bestDir  = null;
        int    bestCount = 0;
        for (Map.Entry<String, List<SignalCandidate>> e : byDirection.entrySet()) {
            if (e.getValue().size() > bestCount) {
                bestCount = e.getValue().size();
                bestDir   = e.getKey();
            }
        }
        if (bestDir == null) return;

        // ── Confluence gate ───────────────────────────────────────────────────
        boolean requireConfluence = Boolean.TRUE.equals(config.getRequireConfluence());
        int minConfluence = config.getMinConfluenceCount() != null ? config.getMinConfluenceCount() : 2;
        if (requireConfluence && bestCount < minConfluence) {
            logger.debug("OPT-BUY: confluence not met - {} source(s) agree on {} (need {})",
                    bestCount, bestDir, minConfluence);
            return;
        }

        // Cooldown check — per direction only (count must not bypass cooldown)
        String cooldownKey = "CONFLUENCE_" + bestDir;
        if (!isCooldownPassed(cooldownKey)) return;

        // Build a combined source label
        List<SignalCandidate> agreed = byDirection.get(bestDir);
        String source = agreed.size() == 1
                ? agreed.get(0).source()
                : agreed.stream().map(SignalCandidate::source).collect(Collectors.joining("+"));

        // Best strength among agreeing candidates
        String strength = agreed.stream()
                .map(SignalCandidate::strength)
                .max(Comparator.comparingInt(this::strengthRank))
                .orElse("MODERATE");

        String optionType = "BUY".equals(bestDir) ? "CE" : "PE";

        if (bestCount > 1) {
            logger.info("OPT-BUY: {} sources agree on {} - placing trade", bestCount, bestDir);
        }

        boolean tradePlaced = placeTrade(liveData, config, source, bestDir, strength, optionType, null, null, null);
        if (!tradePlaced) {
            logger.warn("OPT-BUY: confluence signal {} met but trade placement failed (data or premium filters)", bestDir);
        }
        cooldowns.put(cooldownKey, LocalDateTime.now());
    }

    // ─── ZeroHero (independent) ───────────────────────────────────────────────

    private void checkZeroHero(Map<String, Object> liveData, OptionBuyingConfig config) {
        if (zeroHeroSignalService == null) return;
        try {
            int minScore = config.getZeroHeroMinScore() != null ? config.getZeroHeroMinScore() : 2;
            Map<String, Object> signal = zeroHeroSignalService.checkSignal(liveData, minScore);
            if (signal == null || !Boolean.TRUE.equals(signal.get("hasSignal"))) return;

            String direction   = (String) signal.get("signalType");
            String strength    = signal.get("signalStrength") != null
                    ? (String) signal.get("signalStrength") : "MODERATE";
            String optionType  = (String) signal.get("optionType");

            if (direction == null || optionType == null) return;
            if (!isStrengthSufficient(strength, config.getMinSignalStrength())) return;
            if (!isCooldownPassed(SRC_ZERO_HERO + "_" + direction)) return;

            // ZeroHero supplies its own target/SL/premium (OTM, not ATM)
            Double premium = toDouble(signal, "optionPremium");
            Double target  = toDouble(signal, "targetPrice");
            Double sl      = toDouble(signal, "stopLossPrice");

            // Premium range filter also applies to ZeroHero
            if (!isPremiumInRange(premium, config)) {
                logger.debug("OPT-BUY ZeroHero: premium {} outside range [{}-{}]",
                        premium, config.getMinPremium(), config.getMaxPremium());
                return;
            }

            boolean tradePlaced = placeTrade(liveData, config, SRC_ZERO_HERO, direction, strength, optionType,
                    premium, target, sl);
            if (tradePlaced) {
                sendZeroHeroSignalAlert(signal, premium, target, sl);
            }
        } catch (Exception e) {
            logger.error("OPT-BUY: ZeroHero check error: {}", e.getMessage(), e);
        }
    }

    // ─── Trade placement ──────────────────────────────────────────────────────

    private boolean placeTrade(Map<String, Object> liveData, OptionBuyingConfig config,
                            String source, String signalType, String signalStrength,
                            String optionType,
                            Double overrideEntryPrice, Double overrideTarget, Double overrideSL) {

        String ltpKey    = "CE".equals(optionType) ? "atmCELTP"    : "atmPELTP";
        String tokenKey  = "CE".equals(optionType) ? "atmCEToken"  : "atmPEToken";
        String symbolKey = "CE".equals(optionType) ? "atmCESymbol" : "atmPESymbol";

        Double  entryPrice   = overrideEntryPrice != null ? overrideEntryPrice : toDouble(liveData, ltpKey);
        Long    optionToken  = toLong(liveData, tokenKey);
        String  optionSymbol = (String) liveData.get(symbolKey);
        Integer atmStrike    = toInt(liveData, "atmStrike");

        if (entryPrice == null || entryPrice <= 0 || optionToken == null) {
            logger.debug("OPT-BUY: missing ATM data for {} - skipping", source);
            return false;
        }

        // ── OPTIMIZATION 2: Premium range filter ──────────────────────────────
        if (!isPremiumInRange(entryPrice, config)) {
            logger.info("OPT-BUY: premium {} outside range [{}-{}] - skipping {}",
                    String.format("%.1f", entryPrice),
                    config.getMinPremium(), config.getMaxPremium(), source);
            return false;
        }

        int    quantity    = config.getNumLots() * NIFTY_LOT_SIZE;
        double targetPrice = overrideTarget != null
                ? overrideTarget
                : entryPrice * (1 + config.getTargetPercent() / 100.0);
        double stopLoss    = overrideSL != null
                ? overrideSL
                : entryPrice * (1 - config.getStoplossPercent() / 100.0);

        String tradeId = source + "_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        SimulatedTrade trade = new SimulatedTrade();
        trade.setTradeId(tradeId);
        trade.setTradeDate(LocalDateTime.now());
        trade.setSignalSource(source);
        trade.setSignalType(signalType);
        trade.setSignalStrength(signalStrength);
        trade.setOptionType(optionType);
        trade.setStrikePrice(atmStrike != null ? atmStrike.doubleValue() : null);
        trade.setOptionToken(optionToken);
        trade.setOptionSymbol(optionSymbol);
        trade.setUnderlyingPriceAtEntry(toDouble(liveData, "niftyLTP"));
        trade.setQuantity(quantity);
        trade.setLotSize(NIFTY_LOT_SIZE);
        trade.setNumLots(config.getNumLots());
        trade.setEntryPrice(entryPrice);
        trade.setEntryTime(LocalDateTime.now());
        trade.setEntryReason("OPT_BUY_" + source);
        trade.setTargetPrice(targetPrice);
        trade.setStopLossPrice(stopLoss);
        trade.setStatus(TRADE_STATUS_OPEN);
        trade.setVixAtEntry(toDouble(liveData, "vixValue"));
        trade.setMarketTrend("BUY".equals(signalType) ? "BULLISH" : "BEARISH");

        // Risk/reward ratio
        double risk = entryPrice - stopLoss;
        if (risk > 0) {
            trade.setRiskRewardRatio((targetPrice - entryPrice) / risk);
        }

        // Premium milestone targets (30% / 60% / 100% of move to target)
        double moveToTarget = targetPrice - entryPrice;
        trade.setPremiumT1(entryPrice + moveToTarget * 0.30);
        trade.setPremiumT2(entryPrice + moveToTarget * 0.60);
        trade.setPremiumT3(targetPrice);

        tradeRepository.save(trade);

        // Subscribe to live price feed
        try {
            candlePredictionService.subscribeTokenForJob(List.of(optionToken));
        } catch (Exception e) {
            logger.warn("OPT-BUY: failed to subscribe token {}: {}", optionToken, e.getMessage());
        }

        // Cooldown per individual source (separate from confluence key)
        cooldowns.put(source + "_" + signalType, LocalDateTime.now());

        broadcastEvent("TRADE_OPENED", Map.of(
                "tradeId",     tradeId,
                "source",      source,
                "signalType",  signalType,
                "optionType",  optionType,
                "optionSymbol", optionSymbol != null ? optionSymbol : "",
                "entryPrice",  entryPrice,
                "targetPrice", targetPrice,
                "stopLoss",    stopLoss
        ));

        sendTradeOpenedAlert(trade);

        logger.info("OPT-BUY trade: {} {} {} entry={} target={} SL={} lots={}",
                source, signalType, optionSymbol, entryPrice, targetPrice, stopLoss, config.getNumLots());
        return true;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Trade monitoring
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public void monitorOpenTrades() {
        List<SimulatedTrade> open = getOpenTrades();
        if (open.isEmpty()) return;

        OptionBuyingConfig config = getConfig();
        Map<String, Object> liveData = candlePredictionService.getLiveTickData();
        LocalTime now = LocalTime.now(IST);

        for (SimulatedTrade trade : open) {
            try {
                Double currentPrice = resolveCurrentPrice(trade, liveData);
                if (currentPrice == null) continue;

                // ── OPTIMIZATION 3: Trailing SL update ────────────────────────
                if (Boolean.TRUE.equals(config.getTrailingSlEnabled())) {
                    trade.updateTrailingStopLoss(
                            currentPrice,
                            config.getTrailingActivationPct(),
                            config.getTrailingTrailPct());
                    tradeRepository.save(trade);
                }

                // ── Exit evaluation ────────────────────────────────────────────
                String exitReason = null;
                if (trade.isTargetHit(currentPrice)) {
                    exitReason = EXIT_TARGET_HIT;
                } else if (trade.isStopLossHit(currentPrice)) {
                    // isStopLossHit() already checks trailingStopLoss > fixedSL
                    exitReason = trade.getTrailingStopLoss() != null
                            ? EXIT_TRAILING_SL : EXIT_STOPLOSS_HIT;
                } else if (now.isAfter(HARD_EXIT_TIME)) {
                    exitReason = EXIT_MARKET_CLOSE;
                }

                if (exitReason != null) {
                    exitTrade(trade, currentPrice, exitReason);
                }
            } catch (Exception e) {
                logger.error("OPT-BUY: error monitoring trade {}: {}", trade.getTradeId(), e.getMessage());
            }
        }
    }

    private Double resolveCurrentPrice(SimulatedTrade trade, Map<String, Object> liveData) {
        if (liveData != null && trade.getOptionToken() != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<Long, Object> tokenLtp = (Map<Long, Object>) liveData.get("tokenLTP");
                if (tokenLtp != null) {
                    Object v = tokenLtp.get(trade.getOptionToken());
                    if (v instanceof Number) return ((Number) v).doubleValue();
                }
            } catch (Exception ignored) {}
        }
        if (trade.getOptionToken() != null) {
            return candlePredictionService.getLTPForToken(trade.getOptionToken());
        }
        return null;
    }

    private void exitTrade(SimulatedTrade trade, double exitPrice, String exitReason) {
        trade.setExitPrice(exitPrice);
        trade.setExitTime(LocalDateTime.now());
        trade.setExitReason(exitReason);
        trade.setStatus(TRADE_STATUS_CLOSED);
        trade.calculatePnL();
        tradeRepository.save(trade);

        broadcastEvent("TRADE_CLOSED", Map.of(
                "tradeId",    trade.getTradeId(),
                "exitReason", exitReason,
                "exitPrice",  exitPrice,
                "netPnl",     trade.getNetPnl() != null ? trade.getNetPnl() : 0.0
        ));

        sendTradeClosedAlert(trade);

        logger.info("OPT-BUY exit: {} {} reason={} exitPrice={} netPnl={}",
                trade.getTradeId(), trade.getOptionSymbol(), exitReason, exitPrice, trade.getNetPnl());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Queries
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public List<SimulatedTrade> getOpenTrades() {
        return tradeRepository.findOpenTrades().stream()
                .filter(t -> t.getSignalSource() != null && t.getSignalSource().startsWith(OPT_BUY_PREFIX))
                .collect(Collectors.toList());
    }

    @Override
    public List<SimulatedTrade> getTodayTrades() {
        LocalDateTime start = LocalDate.now(IST).atStartOfDay();
        LocalDateTime end   = LocalDate.now(IST).plusDays(1).atStartOfDay();
        return tradeRepository.findTodaysTrades(start, end).stream()
                .filter(t -> t.getSignalSource() != null && t.getSignalSource().startsWith(OPT_BUY_PREFIX))
                .collect(Collectors.toList());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════════

    private boolean isCooldownPassed(String key) {
        LocalDateTime last = cooldowns.get(key);
        return last == null || last.isBefore(LocalDateTime.now().minusMinutes(SIGNAL_COOLDOWN_MIN));
    }

    private boolean isStrengthSufficient(String actual, String minimum) {
        return strengthRank(actual) >= strengthRank(minimum);
    }

    private int strengthRank(String strength) {
        if ("STRONG".equals(strength))   return 3;
        if ("MODERATE".equals(strength)) return 2;
        return 1;
    }

    /**
     * OPTIMIZATION 2: Premium range filter.
     * Returns true when the premium is within the configured [minPremium, maxPremium] band.
     */
    private boolean isPremiumInRange(Double premium, OptionBuyingConfig config) {
        if (premium == null) return false;
        if (config.getMinPremium() != null && config.getMinPremium() > 0 && premium < config.getMinPremium()) return false;
        if (config.getMaxPremium() != null && config.getMaxPremium() > 0 && premium > config.getMaxPremium()) return false;
        return true;
    }

    // ─── Telegram alerts ──────────────────────────────────────────────────────

    /** Alert fired once per signal event (before trade placement). ZeroHero only. */
    private void sendZeroHeroSignalAlert(Map<String, Object> signal, Double premium,
                                         Double target, Double sl) {
        if (telegramNotificationService == null) return;
        try {
            String direction  = (String) signal.get("signalType");
            String optionType = (String) signal.get("optionType");
            Integer strike    = signal.get("strikePrice") instanceof Number
                    ? ((Number) signal.get("strikePrice")).intValue() : null;
            Integer score     = signal.get("momentumScore") instanceof Number
                    ? ((Number) signal.get("momentumScore")).intValue() : null;
            Object ema9Obj  = signal.get("ema9");
            Object ema21Obj = signal.get("ema21");
            Object rsiObj   = signal.get("rsi");
            Object confObj  = signal.get("confidence");

            String emoji = "BUY".equals(direction) ? "🟢" : "🔴";
            StringBuilder msg = new StringBuilder();
            msg.append(String.format("%s *ZeroHero Signal — %s %s*\n", emoji, direction, optionType));
            msg.append(String.format("🎯 Strike: *%s*\n", strike != null ? strike : "ATM"));
            msg.append(String.format("💰 Premium: *₹%.2f*\n", premium != null ? premium : 0.0));
            msg.append(String.format("🎯 Target: ₹%.2f  (%.0f×)\n",
                    target != null ? target : 0.0,
                    premium != null && premium > 0 && target != null ? target / premium : 0.0));
            msg.append(String.format("🛡️ Stop Loss: ₹%.2f\n", sl != null ? sl : 0.0));
            msg.append("\n📊 *Momentum Indicators*\n");
            msg.append(String.format("• Score: *%s/3*\n", score != null ? score : "?"));
            if (ema9Obj instanceof Number && ema21Obj instanceof Number) {
                double e9  = ((Number) ema9Obj).doubleValue();
                double e21 = ((Number) ema21Obj).doubleValue();
                msg.append(String.format("• EMA9: %.2f  EMA21: %.2f  %s\n",
                        e9, e21, e9 > e21 ? "↑ Bullish" : "↓ Bearish"));
            }
            if (rsiObj instanceof Number) {
                double rsi = ((Number) rsiObj).doubleValue();
                msg.append(String.format("• RSI: %.1f  %s\n",
                        rsi, rsi > 55 ? "↑ Bull" : rsi < 45 ? "↓ Bear" : "Neutral"));
            }
            if (confObj instanceof Number) {
                msg.append(String.format("💪 Confidence: *%.0f%%*\n", ((Number) confObj).doubleValue()));
            }
            msg.append("⚡ *Expiry day — ZeroHero window active*");

            Map<String, Object> tradeData = new HashMap<>();
            tradeData.put("source",     SRC_ZERO_HERO);
            tradeData.put("direction",  direction);
            tradeData.put("optionType", optionType);
            tradeData.put("premium",    premium);
            tradeData.put("score",      score);

            telegramNotificationService.sendTradeAlert("🎯 ZERO HERO SIGNAL", msg.toString(), tradeData);
        } catch (Exception e) {
            logger.warn("OPT-BUY: ZeroHero Telegram signal alert failed: {}", e.getMessage());
        }
    }

    /** Alert fired on every trade opened (all sources). */
    private void sendTradeOpenedAlert(SimulatedTrade trade) {
        if (telegramNotificationService == null) return;
        // Deduplicate: send only once per trade ID
        if (telegramAlertSent.putIfAbsent(trade.getTradeId(), Boolean.TRUE) != null) return;
        try {
            boolean isCE = "CE".equals(trade.getOptionType());
            String emoji = isCE ? "🟢" : "🔴";
            String source = getSourceLabel(trade.getSignalSource());

            StringBuilder msg = new StringBuilder();
            msg.append(String.format("%s *OPT-BUY TRADE OPENED*\n", emoji));
            msg.append(String.format("📌 Source: *%s*\n", source));
            msg.append(String.format("🎯 Symbol: *%s*  [%s]\n",
                    trade.getOptionSymbol() != null ? trade.getOptionSymbol() : "—",
                    trade.getOptionType()));
            msg.append(String.format("💰 Entry Premium: *₹%.2f*\n",
                    trade.getEntryPrice() != null ? trade.getEntryPrice() : 0.0));
            msg.append(String.format("🎯 Target: ₹%.2f\n",
                    trade.getTargetPrice() != null ? trade.getTargetPrice() : 0.0));
            msg.append(String.format("🛡️ Stop Loss: ₹%.2f\n",
                    trade.getStopLossPrice() != null ? trade.getStopLossPrice() : 0.0));
            msg.append(String.format("📦 Qty: %d lots × %d = %d\n",
                    trade.getNumLots() != null ? trade.getNumLots() : 0,
                    trade.getLotSize() != null ? trade.getLotSize() : 65,
                    trade.getQuantity() != null ? trade.getQuantity() : 0));
            if (trade.getUnderlyingPriceAtEntry() != null) {
                msg.append(String.format("📈 NIFTY @ ₹%.2f\n", trade.getUnderlyingPriceAtEntry()));
            }

            Map<String, Object> tradeData = new HashMap<>();
            tradeData.put("tradeId",   trade.getTradeId());
            tradeData.put("symbol",    trade.getOptionSymbol());
            tradeData.put("source",    trade.getSignalSource());
            tradeData.put("entryPrice", trade.getEntryPrice());

            telegramNotificationService.sendTradeAlert("📥 OPT-BUY OPENED", msg.toString(), tradeData);
        } catch (Exception e) {
            logger.warn("OPT-BUY: trade-open Telegram alert failed: {}", e.getMessage());
        }
    }

    /** Alert fired on every trade closed. */
    private void sendTradeClosedAlert(SimulatedTrade trade) {
        if (telegramNotificationService == null) return;
        try {
            double pnl = trade.getNetPnl() != null ? trade.getNetPnl() : 0.0;
            String emoji = pnl >= 0 ? "✅" : "❌";
            String pnlStr = (pnl >= 0 ? "+" : "") + String.format("₹%.0f", pnl);

            StringBuilder msg = new StringBuilder();
            msg.append(String.format("%s *OPT-BUY TRADE CLOSED*  %s\n", emoji, pnlStr));
            msg.append(String.format("📌 Symbol: *%s*\n",
                    trade.getOptionSymbol() != null ? trade.getOptionSymbol() : "—"));
            msg.append(String.format("💰 Entry: ₹%.2f → Exit: ₹%.2f\n",
                    trade.getEntryPrice() != null ? trade.getEntryPrice() : 0.0,
                    trade.getExitPrice() != null ? trade.getExitPrice() : 0.0));
            msg.append(String.format("🚪 Exit Reason: *%s*\n", trade.getExitReason()));
            msg.append(String.format("📦 Qty: %d\n", trade.getQuantity() != null ? trade.getQuantity() : 0));
            msg.append(String.format("💵 Net P&L: *%s*", pnlStr));

            Map<String, Object> tradeData = new HashMap<>();
            tradeData.put("tradeId",    trade.getTradeId());
            tradeData.put("symbol",     trade.getOptionSymbol());
            tradeData.put("exitReason", trade.getExitReason());
            tradeData.put("netPnl",     pnl);

            telegramNotificationService.sendTradeAlert(
                    pnl >= 0 ? "✅ OPT-BUY PROFIT" : "❌ OPT-BUY LOSS",
                    msg.toString(), tradeData);

            // Clean up dedup map entry after trade is closed
            telegramAlertSent.remove(trade.getTradeId());
        } catch (Exception e) {
            logger.warn("OPT-BUY: trade-close Telegram alert failed: {}", e.getMessage());
        }
    }

    private String getSourceLabel(String source) {
        if (source == null) return "";
        return source.replace("OPT_BUY_", "").replace("_", " ");
    }

    private void broadcastEvent(String event, Map<String, Object> payload) {
        if (messagingTemplate == null) return;
        try {
            Map<String, Object> msg = new HashMap<>(payload);
            msg.put("event", event);
            messagingTemplate.convertAndSend("/topic/option-buying", msg);
        } catch (Exception e) {
            logger.warn("OPT-BUY: WebSocket broadcast failed: {}", e.getMessage());
        }
    }

    private static Double toDouble(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : null;
    }

    private static Long toLong(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Number ? ((Number) v).longValue() : null;
    }

    private static Integer toInt(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Number ? ((Number) v).intValue() : null;
    }
}
