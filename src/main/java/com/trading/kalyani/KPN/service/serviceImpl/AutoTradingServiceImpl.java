package com.trading.kalyani.KPN.service.serviceImpl;

import com.trading.kalyani.KPN.config.KiteConnectConfig;
import com.trading.kalyani.KPN.entity.*;
import com.trading.kalyani.KPN.repository.AutoTradeOrderRepository;
import com.trading.kalyani.KPN.repository.AutoTradingConfigRepository;
import com.trading.kalyani.KPN.repository.InternalOrderBlockRepository;
import com.trading.kalyani.KPN.service.AutoTradingService;
import com.trading.kalyani.KPN.service.PerformanceTrackingService;
import com.trading.kalyani.KPN.service.RiskManagementService;
import com.trading.kalyani.KPN.service.TelegramNotificationService;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.OrderParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.LinkedList;

import static com.trading.kalyani.KPN.constants.ApplicationConstants.*;

/**
 * Implementation of AutoTradingService for automated IOB-based trading.
 */
@Service
public class AutoTradingServiceImpl implements AutoTradingService {

    private static final Logger logger = LoggerFactory.getLogger(AutoTradingServiceImpl.class);

    @Autowired
    private AutoTradeOrderRepository orderRepository;

    @Autowired
    private AutoTradingConfigRepository configRepository;

    @Autowired
    private InternalOrderBlockRepository iobRepository;

    @Autowired
    private KiteConnectConfig kiteConnectConfig;

    @Autowired(required = false)
    private PerformanceTrackingService performanceService;

    @Autowired(required = false)
    private RiskManagementService riskManagementService;

    @Autowired(required = false)
    private TelegramNotificationService telegramService;

    // In-memory state
    private final Map<String, Map<String, Object>> openPositions = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> activityLog = Collections.synchronizedList(new LinkedList<>());

    // Cached default config — refreshed on every write; avoids a DB hit on every
    // checkEntryConditions call which can fire multiple times per minute during zone touches.
    private volatile AutoTradingConfig cachedConfig = null;

    // Default values
    private static final String DEFAULT_CONFIG_NAME = "DEFAULT";
    private static final int MAX_ACTIVITY_LOG_SIZE = 1000;

    // Accepts both "9:20" and "09:20" — H allows single-digit hours
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("H:mm");
    private static final ZoneId IST_ZONE_ID = ZoneId.of("Asia/Kolkata");

    // Cache today's completed trade count — refreshed once per minute to avoid a DB
    // hit on every checkEntryConditions call (fires multiple times per minute during zone touches).
    private volatile int cachedTodaysTrades = 0;
    private volatile long cachedTodaysTradesAt = 0L;
    private static final long TRADES_COUNT_CACHE_TTL_MS = 60_000;

    @Value("${autotrade.default.max-daily-loss:10000.0}")
    private double defaultMaxDailyLoss;

    // ==================== Configuration Management ====================

    @Override
    public void setAutoTradingEnabled(boolean enabled) {
        AutoTradingConfig config = getOrCreateDefaultConfig();
        config.setAutoTradingEnabled(enabled);
        configRepository.save(config);
        invalidateConfigCache();

        logActivity("CONFIG", enabled ? "Auto trading ENABLED" : "Auto trading DISABLED", null);
        logger.info("Auto trading {}", enabled ? "enabled" : "disabled");
    }

    @Override
    public boolean isAutoTradingEnabled() {
        AutoTradingConfig config = configRepository.getDefaultConfig();
        return config != null && Boolean.TRUE.equals(config.getAutoTradingEnabled());
    }

    @Override
    public Map<String, Object> getAutoTradingConfig() {
        AutoTradingConfig config = getOrCreateDefaultConfig();
        return convertConfigToMap(config);
    }

    @Override
    @Transactional
    public void updateAutoTradingConfig(Map<String, Object> configMap) {
        AutoTradingConfig config = getOrCreateDefaultConfig();

        if (configMap.containsKey("autoTradingEnabled")) {
            config.setAutoTradingEnabled((Boolean) configMap.get("autoTradingEnabled"));
        }
        if (configMap.containsKey("paperTradingMode")) {
            config.setPaperTradingMode((Boolean) configMap.get("paperTradingMode"));
        }
        if (configMap.containsKey("entryType")) {
            config.setEntryType((String) configMap.get("entryType"));
        }
        if (configMap.containsKey("minConfidence")) {
            config.setMinConfidence(((Number) configMap.get("minConfidence")).doubleValue());
        }
        if (configMap.containsKey("requireFvg")) {
            config.setRequireFvg((Boolean) configMap.get("requireFvg"));
        }
        if (configMap.containsKey("requireTrendAlignment")) {
            config.setRequireTrendAlignment((Boolean) configMap.get("requireTrendAlignment"));
        }
        if (configMap.containsKey("maxPositionSize")) {
            config.setMaxPositionSize(((Number) configMap.get("maxPositionSize")).intValue());
        }
        if (configMap.containsKey("maxOpenPositions")) {
            config.setMaxOpenPositions(((Number) configMap.get("maxOpenPositions")).intValue());
        }
        if (configMap.containsKey("maxDailyTrades")) {
            config.setMaxDailyTrades(((Number) configMap.get("maxDailyTrades")).intValue());
        }
        if (configMap.containsKey("maxDailyLoss")) {
            config.setMaxDailyLoss(((Number) configMap.get("maxDailyLoss")).doubleValue());
        }
        if (configMap.containsKey("enableTrailingSL")) {
            config.setEnableTrailingSL((Boolean) configMap.get("enableTrailingSL"));
        }
        if (configMap.containsKey("trailingSLTrigger")) {
            config.setTrailingSLTrigger((String) configMap.get("trailingSLTrigger"));
        }
        if (configMap.containsKey("bookPartialProfits")) {
            config.setBookPartialProfits((Boolean) configMap.get("bookPartialProfits"));
        }
        if (configMap.containsKey("partialProfitPercent")) {
            config.setPartialProfitPercent(((Number) configMap.get("partialProfitPercent")).intValue());
        }
        if (configMap.containsKey("defaultExitTarget")) {
            config.setDefaultExitTarget((String) configMap.get("defaultExitTarget"));
        }
        if (configMap.containsKey("exitAtMarketClose")) {
            config.setExitAtMarketClose((Boolean) configMap.get("exitAtMarketClose"));
        }
        if (configMap.containsKey("enabledInstruments")) {
            config.setEnabledInstruments((String) configMap.get("enabledInstruments"));
        }
        if (configMap.containsKey("tradeStartTime")) {
            String value = (String) configMap.get("tradeStartTime");
            parseTime("tradeStartTime", value); // validate before saving
            config.setTradeStartTime(value.trim());
        }
        if (configMap.containsKey("tradeEndTime")) {
            String value = (String) configMap.get("tradeEndTime");
            parseTime("tradeEndTime", value);
            config.setTradeEndTime(value.trim());
        }
        if (configMap.containsKey("marketCloseTime")) {
            String value = (String) configMap.get("marketCloseTime");
            parseTime("marketCloseTime", value);
            config.setMarketCloseTime(value.trim());
        }

        configRepository.save(config);
        invalidateConfigCache();
        logActivity("CONFIG", "Configuration updated", configMap);
    }

    @Override
    public void setAutoTradingInstruments(List<Long> instrumentTokens) {
        AutoTradingConfig config = getOrCreateDefaultConfig();
        config.setEnabledInstruments(instrumentTokens.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",")));
        configRepository.save(config);
        invalidateConfigCache();
    }

    @Override
    public List<Long> getAutoTradingInstruments() {
        AutoTradingConfig config = configRepository.getDefaultConfig();
        String raw = config != null ? config.getEnabledInstruments() : null;

        if (raw == null || raw.isBlank()) {
            return List.of(NIFTY_INSTRUMENT_TOKEN);
        }

        List<Long> tokens = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .flatMap(s -> {
                    try {
                        return java.util.stream.Stream.of(Long.parseLong(s));
                    } catch (NumberFormatException e) {
                        logger.warn("Skipping invalid instrument token '{}' in enabledInstruments", s);
                        return java.util.stream.Stream.empty();
                    }
                })
                .toList();

        return tokens.isEmpty() ? List.of(NIFTY_INSTRUMENT_TOKEN) : tokens;
    }

    private AutoTradingConfig getOrCreateDefaultConfig() {
        if (cachedConfig != null) return cachedConfig;
        AutoTradingConfig config = configRepository.getDefaultConfig();
        if (config == null) {
            config = createDefaultConfig();
            configRepository.save(config);
        }
        cachedConfig = config;
        return config;
    }

    /** Invalidates the in-memory config cache — call after any config write. */
    private void invalidateConfigCache() {
        cachedConfig = null;
    }

    /**
     * Returns today's completed entry count, cached for TRADES_COUNT_CACHE_TTL_MS.
     * Avoids a DB hit on every checkEntryConditions call during zone touches.
     */
    private int getCachedTodaysTrades() {
        long now = System.currentTimeMillis();
        if (now - cachedTodaysTradesAt > TRADES_COUNT_CACHE_TTL_MS) {
            cachedTodaysTrades = orderRepository.countTodaysCompletedEntries(
                    LocalDate.now(IST_ZONE_ID).atStartOfDay());
            cachedTodaysTradesAt = now;
        }
        return cachedTodaysTrades;
    }

    /** Call after a trade is placed to force an immediate refresh of the count. */
    private void invalidateTodaysTradesCache() {
        cachedTodaysTradesAt = 0L;
    }

    private LocalTime parseTime(String fieldName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        try {
            return LocalTime.parse(value.trim(), TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    fieldName + " must be in HH:mm format (e.g. 09:20), got: '" + value + "'");
        }
    }

    private AutoTradingConfig createDefaultConfig() {
        return AutoTradingConfig.builder()
                .configName(DEFAULT_CONFIG_NAME)
                .autoTradingEnabled(false)
                .paperTradingMode(true) // Start with paper trading
                .entryType(ENTRY_TYPE_ZONE_TOUCH)
                .minConfidence(65.0)
                .requireFvg(false)
                .requireTrendAlignment(true)
                .requireInstitutionalVolume(false)
                .maxPositionSize(50)
                .maxLotsPerTrade(1)
                .maxOpenPositions(2)
                .maxDailyTrades(5)
                .maxDailyLoss(defaultMaxDailyLoss)
                .useDynamicSL(true)
                .slAtrMultiplier(1.5)
                .enableTrailingSL(true)
                .trailingSLTrigger(TRAILING_SL_TRIGGER_TARGET_1)
                .trailingSLDistancePoints(20.0)
                .bookPartialProfits(true)
                .partialProfitPercent(50)
                .partialProfitAt(TRADE_TARGET_1)
                .defaultExitTarget(TRADE_TARGET_2)
                .exitAtMarketClose(true)
                .marketCloseTime("15:20")
                .tradeStartTime("09:20")
                .tradeEndTime("15:00")
                .avoidFirstCandle(true)
                .enabledInstruments(String.valueOf(NIFTY_INSTRUMENT_TOKEN))
                .defaultProductType(PRODUCT_TYPE_MIS)
                .useBracketOrders(false)
                .useCoverOrders(false)
                .notifyOnEntry(true)
                .notifyOnExit(true)
                .notifyOnSLHit(true)
                .notifyOnTargetHit(true)
                .build();
    }

    // ==================== Entry Condition Monitoring ====================

    @Override
    public Map<String, Object> checkEntryConditions(Long iobId, Double currentPrice) {
        Optional<InternalOrderBlock> iobOpt = iobRepository.findById(iobId);
        if (iobOpt.isEmpty()) {
            return Map.of("iobId", iobId, "valid", false, "reason", "IOB not found");
        }
        return checkEntryConditions(iobOpt.get(), getOrCreateDefaultConfig(), currentPrice);
    }

    private Map<String, Object> checkEntryConditions(InternalOrderBlock iob, AutoTradingConfig config, Double currentPrice) {
        Map<String, Object> result = new HashMap<>();
        result.put("iobId", iob.getId());
        result.put("currentPrice", currentPrice);

        List<String> failedConditions = new ArrayList<>();
        List<String> passedConditions = new ArrayList<>();

        // Short-circuit on hard-invalid conditions — no point running DB queries
        // or time checks if the IOB itself is not tradeable.
        if (!"FRESH".equals(iob.getStatus())) {
            result.put("passedConditions", passedConditions);
            result.put("failedConditions", List.of("IOB is not FRESH: " + iob.getStatus()));
            result.put("valid", false);
            result.put("zoneTouched", false);
            return result;
        }
        passedConditions.add("IOB is FRESH");

        // Check confidence threshold — prefer enhancedConfidence if set, fall back to signalConfidence
        Double iobConfidence = iob.getEnhancedConfidence() != null
                ? iob.getEnhancedConfidence() : iob.getSignalConfidence();
        double minConf = config.getMinConfidence() != null ? config.getMinConfidence() : 65.0;
        if (iobConfidence != null && iobConfidence >= minConf) {
            passedConditions.add(String.format("Confidence meets threshold: %.1f%% >= %.1f%%",
                    iobConfidence, minConf));
        } else {
            failedConditions.add(String.format("Confidence too low: %s < %.1f%%",
                    iobConfidence != null ? String.format("%.1f%%", iobConfidence) : "null", minConf));
        }

        // Check FVG requirement
        if (Boolean.TRUE.equals(config.getRequireFvg())) {
            if (Boolean.TRUE.equals(iob.getHasFvg())) {
                passedConditions.add("Has FVG confluence");
            } else {
                failedConditions.add("FVG required but not present");
            }
        }

        // Check trend alignment requirement
        if (Boolean.TRUE.equals(config.getRequireTrendAlignment())) {
            if (Boolean.TRUE.equals(iob.getTrendAligned())) {
                passedConditions.add("Trade is trend-aligned");
            } else {
                failedConditions.add("Trend alignment required but not met");
            }
        }

        // Check zone touch
        boolean zoneTouched = isZoneTouched(iob, currentPrice);
        if (zoneTouched) {
            passedConditions.add("Price is in zone");
        } else {
            failedConditions.add("Price not in zone yet");
        }

        // Check trading hours — must use IST, not system timezone
        LocalTime nowIST = LocalTime.now(IST_ZONE_ID);
        try {
            LocalTime startTime = parseTime("tradeStartTime", config.getTradeStartTime());
            LocalTime endTime   = parseTime("tradeEndTime",   config.getTradeEndTime());
            if (nowIST.isAfter(startTime) && nowIST.isBefore(endTime)) {
                passedConditions.add("Within trading hours");
            } else {
                failedConditions.add(String.format("Outside trading hours (%s, window %s–%s)",
                        nowIST, startTime, endTime));
            }
        } catch (IllegalArgumentException e) {
            // Misconfigured time string — fail safe rather than throwing and blocking all trades
            failedConditions.add("Trading hours config invalid: " + e.getMessage());
            logger.warn("checkEntryConditions: {}", e.getMessage());
        }

        // Check daily trade limit — cached for TRADES_COUNT_CACHE_TTL_MS to avoid per-tick DB hits
        int todaysTrades = getCachedTodaysTrades();
        if (todaysTrades < config.getMaxDailyTrades()) {
            passedConditions.add("Daily trade limit not exceeded: " +
                    todaysTrades + "/" + config.getMaxDailyTrades());
        } else {
            failedConditions.add("Daily trade limit reached: " + todaysTrades);
        }

        // Check max open positions
        if (openPositions.size() < config.getMaxOpenPositions()) {
            passedConditions.add("Position limit not exceeded: " +
                    openPositions.size() + "/" + config.getMaxOpenPositions());
        } else {
            failedConditions.add("Max open positions reached: " + openPositions.size());
        }

        result.put("passedConditions", passedConditions);
        result.put("failedConditions", failedConditions);
        result.put("valid", failedConditions.isEmpty());
        result.put("zoneTouched", zoneTouched);

        return result;
    }

    // 0.1% buffer — price approaching zone within this distance counts as a touch
    private static final double ZONE_APPROACH_BUFFER_PERCENT = 0.1;

    @Override
    public boolean isZoneTouched(InternalOrderBlock iob, Double currentPrice) {
        if (iob == null || currentPrice == null ||
                iob.getZoneHigh() == null || iob.getZoneLow() == null) {
            return false;
        }

        // Price must be strictly inside the zone — no buffer allowed.
        // Entry is only valid when price actually re-enters the IOB zone.
        return currentPrice >= iob.getZoneLow() && currentPrice <= iob.getZoneHigh();
    }

    @Override
    public boolean hasConfirmationCandle(InternalOrderBlock iob) {
        // This would require real-time candle data
        // For now, return true if zone is touched
        return true;
    }

    @Override
    public List<InternalOrderBlock> getIOBsReadyForEntry(Long instrumentToken, Double currentPrice) {
        return getIOBsReadyForEntry(instrumentToken, currentPrice, getOrCreateDefaultConfig());
    }

    private List<InternalOrderBlock> getIOBsReadyForEntry(Long instrumentToken, Double currentPrice, AutoTradingConfig config) {
        List<InternalOrderBlock> freshIOBs = iobRepository.findFreshIOBs(instrumentToken);
        if (freshIOBs.isEmpty()) {
            return List.of();
        }

        double minConfidence = config.getMinConfidence() != null ? config.getMinConfidence() : 65.0;

        // Pre-fetch all IOB IDs that already have orders in one query (avoids N+1)
        List<Long> iobIds = freshIOBs.stream()
                .map(InternalOrderBlock::getId)
                .filter(id -> id != null)
                .toList();
        Set<Long> iobIdsWithOrders = orderRepository.findExistingOrderIobIds(iobIds);

        return freshIOBs.stream()
                .filter(iob -> {
                    // Prefer enhancedConfidence if set, same as IOBAutoTradeServiceImpl
                    Double conf = iob.getEnhancedConfidence() != null
                            ? iob.getEnhancedConfidence() : iob.getSignalConfidence();
                    if (conf == null || conf < minConfidence) {
                        logger.warn("AUTO-TRADE SKIP IOB#{} {}: confidence {}% < min {}%",
                                iob.getId(), iob.getObType(),
                                conf != null ? String.format("%.1f", conf) : "null",
                                String.format("%.1f", minConfidence));
                        return false;
                    }
                    if (!isZoneTouched(iob, currentPrice)) {
                        logger.info("AUTO-TRADE SKIP IOB#{} {}: price {} not in zone [{} – {}]",
                                iob.getId(), iob.getObType(),
                                String.format("%.2f", currentPrice),
                                iob.getZoneLow() != null ? String.format("%.2f", iob.getZoneLow()) : "null",
                                iob.getZoneHigh() != null ? String.format("%.2f", iob.getZoneHigh()) : "null");
                        return false;
                    }
                    if (iob.getId() == null || iobIdsWithOrders.contains(iob.getId())) {
                        logger.warn("AUTO-TRADE SKIP IOB#{} {}: AutoTradeOrder already exists for this IOB",
                                iob.getId(), iob.getObType());
                        return false;
                    }
                    return true;
                })
                .toList();
    }

    // ==================== Order Management ====================

    @Override
    public Map<String, Object> placeIOBOrder(Long iobId, Double entryPrice, Integer quantity) {
        Map<String, Object> result = new HashMap<>();

        Optional<InternalOrderBlock> iobOpt = iobRepository.findById(iobId);
        if (iobOpt.isEmpty()) {
            result.put("success", false);
            result.put("error", "IOB not found");
            return result;
        }

        InternalOrderBlock iob = iobOpt.get();
        AutoTradingConfig config = getOrCreateDefaultConfig();

        // Pass already-fetched iob and config to avoid re-fetching inside checkEntryConditions
        Map<String, Object> conditions = checkEntryConditions(iob, config, entryPrice);
        if (!Boolean.TRUE.equals(conditions.get("valid"))) {
            logger.warn("AUTO-TRADE BLOCKED IOB#{} {}: failed conditions: {}",
                    iobId, iob.getObType(), conditions.get("failedConditions"));
            result.put("success", false);
            result.put("error", "Entry conditions not met");
            result.put("failedConditions", conditions.get("failedConditions"));
            return result;
        }

        // Determine quantity — null-safe unboxing
        int qty = quantity != null ? quantity
                : (config.getMaxPositionSize() != null ? config.getMaxPositionSize() : 1);

        // Use risk management for position sizing if available
        if (riskManagementService != null) {
            Map<String, Object> sizing = riskManagementService.calculatePositionSizeForIOB(iob);
            if (sizing != null && sizing.get("calculatedQuantity") != null) {
                int riskQty = ((Number) sizing.get("calculatedQuantity")).intValue();
                if (riskQty <= 0) {
                    result.put("success", false);
                    result.put("error", "Position sizing returned 0 — risk per unit exceeds max risk allowed");
                    return result;
                }
                qty = Math.min(qty, riskQty);
            }
        }

        // Create order
        AutoTradeOrder order = AutoTradeOrder.builder()
                .iobId(iobId)
                .instrumentToken(iob.getInstrumentToken())
                .instrumentName(iob.getInstrumentName())
                .orderType("LIMIT")
                .transactionType("LONG".equals(iob.getTradeDirection()) ? "BUY" : "SELL")
                .productType(config.getDefaultProductType())
                .quantity(qty)
                .price(entryPrice)
                .status("PENDING")
                .orderPurpose("ENTRY")
                .orderTime(LocalDateTime.now(IST_ZONE_ID))
                .build();

        boolean orderSucceeded;

        // Check if paper trading mode
        if (Boolean.TRUE.equals(config.getPaperTradingMode())) {
            order.setStatus("COMPLETE");
            order.setFilledQuantity(qty);
            order.setAveragePrice(entryPrice);
            order.setFillTime(LocalDateTime.now(IST_ZONE_ID));
            order.setKiteOrderId("PAPER_" + System.currentTimeMillis());

            if (performanceService != null) {
                TradeResult trade = performanceService.createTradeFromIOB(
                        iob, entryPrice, qty, "SIMULATED", "AUTO_ZONE_TOUCH");
                order.setTradeResultId(trade.getId());
            }

            addOpenPosition(order, iob);
            result.put("mode", "PAPER_TRADING");
            orderSucceeded = true;
        } else {
            // Kite API call happens outside @Transactional to avoid holding DB connection during network I/O
            Map<String, Object> kiteResult = placeKiteOrder(order);
            if (Boolean.TRUE.equals(kiteResult.get("success"))) {
                order.setKiteOrderId((String) kiteResult.get("orderId"));
                order.setStatus("PLACED");
                orderSucceeded = true;
            } else {
                order.setStatus("REJECTED");
                order.setErrorMessage((String) kiteResult.get("error"));
                orderSucceeded = false;
            }
        }

        // Persist order and conditionally update IOB in one transaction
        saveOrderAndUpdateIOB(order, iob, orderSucceeded);

        if (!orderSucceeded) {
            result.put("success", false);
            result.put("error", order.getErrorMessage());
            result.put("order", convertOrderToMap(order));
            return result;
        }

        // Send notification
        if (Boolean.TRUE.equals(config.getNotifyOnEntry()) && telegramService != null) {
            sendEntryNotification(iob, order);
        }

        logActivity("ENTRY", "Order placed for IOB " + iobId, Map.of(
                "orderId", order.getOrderId(),
                "price", entryPrice,
                "quantity", qty
        ));

        result.put("success", true);
        result.put("order", convertOrderToMap(order));
        return result;
    }

    @Transactional
    protected void saveOrderAndUpdateIOB(AutoTradeOrder order, InternalOrderBlock iob, boolean markTraded) {
        orderRepository.save(order);
        if (markTraded) {
            iob.setStatus("TRADED");
            iob.setTradeTaken(true);
            iob.setTradeId(order.getOrderId());
            iobRepository.save(iob);
        }
    }

    @Override
    public Map<String, Object> placeMarketOrder(Long iobId, Integer quantity) {
        Optional<InternalOrderBlock> iobOpt = iobRepository.findById(iobId);
        if (iobOpt.isEmpty()) {
            return Map.of("success", false, "error", "IOB not found");
        }
        return placeIOBOrder(iobId, iobOpt.get().getZoneMidpoint(), quantity);
    }

    @Override
    public Map<String, Object> placeLimitOrder(Long iobId, Double limitPrice, Integer quantity) {
        return placeIOBOrder(iobId, limitPrice, quantity);
    }

    @Override
    @Transactional
    public Map<String, Object> cancelOrder(String orderId) {
        Map<String, Object> result = new HashMap<>();

        Optional<AutoTradeOrder> orderOpt = orderRepository.findByOrderId(orderId);
        if (orderOpt.isEmpty()) {
            result.put("success", false);
            result.put("error", "Order not found");
            return result;
        }

        AutoTradeOrder order = orderOpt.get();

        if (!"PENDING".equals(order.getStatus()) && !"PLACED".equals(order.getStatus())) {
            result.put("success", false);
            result.put("error", "Order cannot be cancelled: " + order.getStatus());
            return result;
        }

        // Cancel on Kite if real order
        AutoTradingConfig config = getOrCreateDefaultConfig();
        if (!Boolean.TRUE.equals(config.getPaperTradingMode()) && order.getKiteOrderId() != null) {
            // Call Kite cancel order API
            // kiteService.cancelOrder(order.getKiteOrderId());
        }

        order.setStatus("CANCELLED");
        orderRepository.save(order);

        logActivity("CANCEL", "Order cancelled: " + orderId, null);

        result.put("success", true);
        result.put("message", "Order cancelled");
        return result;
    }

    @Override
    public Map<String, Object> getOrderStatus(String orderId) {
        return orderRepository.findByOrderId(orderId)
                .map(this::convertOrderToMap)
                .orElse(Map.of("error", "Order not found"));
    }

    @Override
    public List<Map<String, Object>> getPendingOrders() {
        return orderRepository.findPendingOrders().stream()
                .map(this::convertOrderToMap)
                .toList();
    }

    // ==================== Position Management ====================

    @Override
    public List<Map<String, Object>> getOpenPositions() {
        return new ArrayList<>(openPositions.values());
    }

    @Override
    @Transactional
    public Map<String, Object> updateTrailingStop(String positionId, Double newStopLoss) {
        Map<String, Object> position = openPositions.get(positionId);
        if (position == null) {
            return Map.of("success", false, "error", "Position not found");
        }

        Double currentSL = (Double) position.get("stopLoss");
        boolean isLong = "LONG".equals(position.get("direction"));

        // Validate trailing stop (can only move in favorable direction)
        if (isLong && newStopLoss < currentSL) {
            return Map.of("success", false, "error", "Trailing SL can only move up for long positions");
        }
        if (!isLong && newStopLoss > currentSL) {
            return Map.of("success", false, "error", "Trailing SL can only move down for short positions");
        }

        position.put("stopLoss", newStopLoss);
        position.put("trailingStopActive", true);

        // Update trade result if exists
        Long tradeResultId = (Long) position.get("tradeResultId");
        if (tradeResultId != null && performanceService != null) {
            performanceService.updateTrailingStop(tradeResultId, newStopLoss);
        }

        logActivity("TRAILING_SL", "Trailing stop updated: " + positionId, Map.of(
                "oldSL", currentSL,
                "newSL", newStopLoss
        ));

        return Map.of("success", true, "newStopLoss", newStopLoss);
    }

    @Override
    @Transactional
    public Map<String, Object> bookPartialProfits(String positionId, int percentage, Double price) {
        Map<String, Object> position = openPositions.get(positionId);
        if (position == null) {
            return Map.of("success", false, "error", "Position not found");
        }

        int totalQty = (Integer) position.get("quantity");
        int exitQty = (totalQty * percentage) / 100;

        if (exitQty <= 0) {
            return Map.of("success", false, "error", "Exit quantity too small");
        }

        int remainingQty = totalQty - exitQty;
        position.put("quantity", remainingQty);
        position.put("partialProfitBooked", true);
        position.put("partialExitPrice", price);
        position.put("partialExitQty", exitQty);

        logActivity("PARTIAL_EXIT", "Partial profits booked: " + positionId, Map.of(
                "exitQty", exitQty,
                "remainingQty", remainingQty,
                "exitPrice", price
        ));

        return Map.of(
                "success", true,
                "exitQuantity", exitQty,
                "remainingQuantity", remainingQty,
                "exitPrice", price
        );
    }

    @Override
    @Transactional
    public Map<String, Object> closePosition(String positionId, Double exitPrice, String reason) {
        Map<String, Object> position = openPositions.remove(positionId);
        if (position == null) {
            return Map.of("success", false, "error", "Position not found");
        }

        AutoTradingConfig config = getOrCreateDefaultConfig();

        // Close trade result
        Long tradeResultId = (Long) position.get("tradeResultId");
        if (tradeResultId != null && performanceService != null) {
            performanceService.closeTradeResult(tradeResultId, exitPrice, LocalDateTime.now(IST_ZONE_ID), reason);
        }

        // Create exit order
        AutoTradeOrder exitOrder = AutoTradeOrder.builder()
                .instrumentToken((Long) position.get("instrumentToken"))
                .instrumentName((String) position.get("instrumentName"))
                .orderType("MARKET")
                .transactionType("LONG".equals(position.get("direction")) ? "SELL" : "BUY")
                .productType(config.getDefaultProductType())
                .quantity((Integer) position.get("quantity"))
                .price(exitPrice)
                .averagePrice(exitPrice)
                .filledQuantity((Integer) position.get("quantity"))
                .status("COMPLETE")
                .orderPurpose(reason)
                .parentOrderId((String) position.get("entryOrderId"))
                .orderTime(LocalDateTime.now(IST_ZONE_ID))
                .fillTime(LocalDateTime.now(IST_ZONE_ID))
                .build();

        orderRepository.save(exitOrder);

        // Send notification
        if (telegramService != null) {
            boolean isSLHit = "STOP_LOSS".equals(reason) || "TRAILING_SL".equals(reason);
            boolean isTargetHit = reason != null && reason.startsWith("TARGET");

            if ((isSLHit && Boolean.TRUE.equals(config.getNotifyOnSLHit())) ||
                (isTargetHit && Boolean.TRUE.equals(config.getNotifyOnTargetHit())) ||
                Boolean.TRUE.equals(config.getNotifyOnExit())) {
                sendExitNotification(position, exitPrice, reason);
            }
        }

        logActivity("EXIT", "Position closed: " + positionId, Map.of(
                "exitPrice", exitPrice,
                "reason", reason
        ));

        return Map.of(
                "success", true,
                "positionId", positionId,
                "exitPrice", exitPrice,
                "reason", reason
        );
    }

    @Override
    @Transactional
    public Map<String, Object> closeAllPositions(Long instrumentToken) {
        Map<String, Object> result = new HashMap<>();
        List<String> closedPositions = new ArrayList<>();

        for (Map.Entry<String, Map<String, Object>> entry : openPositions.entrySet()) {
            Map<String, Object> position = entry.getValue();
            Long posToken = (Long) position.get("instrumentToken");

            if (instrumentToken == null || instrumentToken.equals(posToken)) {
                Double currentPrice = (Double) position.get("currentPrice");
                closePosition(entry.getKey(), currentPrice, "CLOSE_ALL");
                closedPositions.add(entry.getKey());
            }
        }

        result.put("success", true);
        result.put("closedPositions", closedPositions);
        result.put("count", closedPositions.size());
        return result;
    }

    // ==================== Auto Trade Execution Loop ====================

    @Override
    public void processAutoTrades(Map<Long, Double> currentPrices) {
        if (!isAutoTradingEnabled()) {
            logger.debug("AUTO-TRADE: disabled — skipping processAutoTrades");
            return;
        }

        AutoTradingConfig config = getOrCreateDefaultConfig();

        // Check trading hours
        LocalTime now = LocalTime.now(IST_ZONE_ID);
        LocalTime startTime = parseTime("tradeStartTime", config.getTradeStartTime());
        LocalTime endTime = parseTime("tradeEndTime", config.getTradeEndTime());

        if (now.isBefore(startTime) || now.isAfter(endTime)) {
            return;
        }

        // Check daily limits — null-safe unboxing
        int maxDailyTrades = config.getMaxDailyTrades() != null ? config.getMaxDailyTrades() : 5;
        int todaysTrades = orderRepository.countTodaysCompletedEntries(
                LocalDate.now(IST_ZONE_ID).atStartOfDay());
        if (todaysTrades >= maxDailyTrades) {
            return;
        }

        int maxOpenPositions = config.getMaxOpenPositions() != null ? config.getMaxOpenPositions() : 2;

        // Process each enabled instrument
        for (Long token : getAutoTradingInstruments()) {
            Double currentPrice = currentPrices.get(token);
            if (currentPrice == null) continue;

            // pass config down to avoid a second getOrCreateDefaultConfig() call inside
            List<InternalOrderBlock> readyIOBs = getIOBsReadyForEntry(token, currentPrice, config);

            for (InternalOrderBlock iob : readyIOBs) {
                if (openPositions.size() >= maxOpenPositions) {
                    break;
                }

                Double entryPrice = iob.getZoneMidpoint();
                if (entryPrice == null) {
                    logger.warn("Skipping IOB {} — zoneMidpoint is null", iob.getId());
                    continue;
                }

                try {
                    placeIOBOrder(iob.getId(), entryPrice, null);
                    logger.info("Auto trade placed for IOB {}", iob.getId());
                } catch (Exception e) {
                    logger.error("Error placing auto trade for IOB {}: {}", iob.getId(), e.getMessage());
                }
            }
        }

        // Manage open positions
        manageOpenPositions(currentPrices);

        // Check for market close exit
        if (Boolean.TRUE.equals(config.getExitAtMarketClose())) {
            LocalTime closeTime = parseTime("marketCloseTime", config.getMarketCloseTime());
            if (now.isAfter(closeTime)) {
                closeAllPositions(null);
            }
        }
    }

    @Override
    public void manageOpenPositions(Map<Long, Double> currentPrices) {
        AutoTradingConfig config = getOrCreateDefaultConfig();

        for (Map.Entry<String, Map<String, Object>> entry : openPositions.entrySet()) {
            String positionId = entry.getKey();
            Map<String, Object> position = entry.getValue();

            Long token = (Long) position.get("instrumentToken");
            Double currentPrice = currentPrices.get(token);
            if (currentPrice == null) continue;

            position.put("currentPrice", currentPrice);

            Double entryPrice = (Double) position.get("entryPrice");
            Double stopLoss = (Double) position.get("stopLoss");
            Double target1 = (Double) position.get("target1");
            Double target2 = (Double) position.get("target2");
            Double target3 = (Double) position.get("target3");
            boolean isLong = "LONG".equals(position.get("direction"));

            // Check stop loss
            if (stopLoss != null) {
                boolean slHit = isLong ? currentPrice <= stopLoss : currentPrice >= stopLoss;
                if (slHit) {
                    String reason = Boolean.TRUE.equals(position.get("trailingStopActive")) ?
                            "TRAILING_SL" : "STOP_LOSS";
                    closePosition(positionId, currentPrice, reason);
                    continue;
                }
            }

            // Check targets
            String exitTarget = config.getDefaultExitTarget();
            Double targetPrice = null;
            if ("TARGET_3".equals(exitTarget)) targetPrice = target3;
            else if ("TARGET_2".equals(exitTarget)) targetPrice = target2;
            else if ("TARGET_1".equals(exitTarget)) targetPrice = target1;

            if (targetPrice != null) {
                boolean targetHit = isLong ? currentPrice >= targetPrice : currentPrice <= targetPrice;
                if (targetHit) {
                    closePosition(positionId, currentPrice, exitTarget);
                    continue;
                }
            }

            // Trailing stop logic
            if (Boolean.TRUE.equals(config.getEnableTrailingSL())) {
                String trailTrigger = config.getTrailingSLTrigger();

                if ("TARGET_1".equals(trailTrigger) && target1 != null) {
                    boolean t1Hit = isLong ? currentPrice >= target1 : currentPrice <= target1;
                    if (t1Hit && !Boolean.TRUE.equals(position.get("trailingStopActive"))) {
                        // Move SL to entry (breakeven)
                        updateTrailingStop(positionId, entryPrice);
                    }
                }

                // Trail stop as price moves
                if (Boolean.TRUE.equals(position.get("trailingStopActive"))) {
                    Double trailDistance = config.getTrailingSLDistancePoints();
                    if (trailDistance != null) {
                        Double newSL = isLong ? currentPrice - trailDistance : currentPrice + trailDistance;
                        if ((isLong && newSL > stopLoss) || (!isLong && newSL < stopLoss)) {
                            updateTrailingStop(positionId, newSL);
                        }
                    }
                }
            }

            // Partial profit booking
            if (Boolean.TRUE.equals(config.getBookPartialProfits()) &&
                    !Boolean.TRUE.equals(position.get("partialProfitBooked"))) {
                String partialAt = config.getPartialProfitAt();
                Double partialTarget = "TARGET_1".equals(partialAt) ? target1 : target2;

                if (partialTarget != null) {
                    boolean partialTargetHit = isLong ?
                            currentPrice >= partialTarget : currentPrice <= partialTarget;
                    if (partialTargetHit) {
                        bookPartialProfits(positionId, config.getPartialProfitPercent(), currentPrice);
                    }
                }
            }
        }
    }

    @Override
    public void processPendingEntries(Map<Long, Double> currentPrices) {
        List<AutoTradeOrder> pendingOrders = orderRepository.findPendingByPurpose("ENTRY");

        for (AutoTradeOrder order : pendingOrders) {
            Double currentPrice = currentPrices.get(order.getInstrumentToken());
            if (currentPrice == null) continue;

            // Check if limit order should be filled
            if ("LIMIT".equals(order.getOrderType()) && order.getPrice() != null) {
                boolean shouldFill = "BUY".equals(order.getTransactionType()) ?
                        currentPrice <= order.getPrice() : currentPrice >= order.getPrice();

                if (shouldFill) {
                    order.setStatus("COMPLETE");
                    order.setFilledQuantity(order.getQuantity());
                    order.setAveragePrice(order.getPrice());
                    order.setFillTime(LocalDateTime.now(IST_ZONE_ID));
                    orderRepository.save(order);

                    // Add to open positions
                    Optional<InternalOrderBlock> iobOpt = iobRepository.findById(order.getIobId());
                    iobOpt.ifPresent(iob -> addOpenPosition(order, iob));
                }
            }
        }
    }

    // ==================== Statistics & Reports ====================

    @Override
    public Map<String, Object> getAutoTradingStats() {
        Map<String, Object> stats = new HashMap<>();

        LocalDateTime todayStart = LocalDate.now(IST_ZONE_ID).atStartOfDay();
        List<AutoTradeOrder> todaysOrders = orderRepository.findTodaysOrders(todayStart);

        stats.put("isEnabled", isAutoTradingEnabled());
        stats.put("openPositions", openPositions.size());
        stats.put("todaysOrders", todaysOrders.size());
        stats.put("todaysEntries", todaysOrders.stream()
                .filter(o -> "ENTRY".equals(o.getOrderPurpose()) && "COMPLETE".equals(o.getStatus()))
                .count());
        stats.put("pendingOrders", orderRepository.findPendingOrders().size());

        AutoTradingConfig config = getOrCreateDefaultConfig();
        stats.put("paperTradingMode", config.getPaperTradingMode());
        stats.put("maxDailyTrades", config.getMaxDailyTrades());
        stats.put("maxOpenPositions", config.getMaxOpenPositions());

        return stats;
    }

    @Override
    public List<Map<String, Object>> getTodaysAutoTrades() {
        LocalDateTime todayStart = LocalDate.now(IST_ZONE_ID).atStartOfDay();
        return orderRepository.findTodaysOrders(todayStart).stream()
                .map(this::convertOrderToMap)
                .toList();
    }

    @Override
    public List<Map<String, Object>> getActivityLog(int limit) {
        int size = Math.min(limit, activityLog.size());
        return new ArrayList<>(activityLog.subList(
                Math.max(0, activityLog.size() - size), activityLog.size()));
    }

    // ==================== Helper Methods ====================

    private void addOpenPosition(AutoTradeOrder order, InternalOrderBlock iob) {
        Map<String, Object> position = new HashMap<>();
        position.put("positionId", order.getOrderId());
        position.put("iobId", iob.getId());
        position.put("tradeResultId", order.getTradeResultId());
        position.put("entryOrderId", order.getOrderId());
        position.put("instrumentToken", order.getInstrumentToken());
        position.put("instrumentName", order.getInstrumentName());
        position.put("direction", iob.getTradeDirection());
        position.put("quantity", order.getFilledQuantity());
        position.put("entryPrice", order.getAveragePrice());
        position.put("entryTime", order.getFillTime());
        position.put("stopLoss", iob.getStopLoss());
        position.put("target1", iob.getTarget1());
        position.put("target2", iob.getTarget2());
        position.put("target3", iob.getTarget3());
        position.put("currentPrice", order.getAveragePrice());
        position.put("trailingStopActive", false);
        position.put("partialProfitBooked", false);

        openPositions.put(order.getOrderId(), position);
    }

    private Map<String, Object> placeKiteOrder(AutoTradeOrder order) {
        Map<String, Object> result = new HashMap<>();
        try {
            KiteConnect kite = kiteConnectConfig.kiteConnect();
            if (kite.getAccessToken() == null) {
                result.put("success", false);
                result.put("error", "Kite access token not set — user not logged in");
                return result;
            }

            OrderParams params = new OrderParams();
            params.tradingsymbol = order.getTradingSymbol();
            params.exchange      = order.getExchange();
            params.transactionType = "BUY".equals(order.getTransactionType())
                    ? Constants.TRANSACTION_TYPE_BUY : Constants.TRANSACTION_TYPE_SELL;
            params.orderType     = mapOrderType(order.getOrderType());
            params.product       = mapProductType(order.getProductType());
            params.quantity      = order.getQuantity();
            params.price         = order.getPrice() != null ? order.getPrice() : 0.0;
            params.triggerPrice  = order.getTriggerPrice() != null ? order.getTriggerPrice() : 0.0;
            params.validity      = Constants.VALIDITY_DAY;
            params.tag           = "KPN_IOB";

            Order kiteOrder = kite.placeOrder(params, "regular");
            logger.info("Kite order placed: orderId={} for {}", kiteOrder.orderId, order.getTradingSymbol());

            result.put("success", true);
            result.put("orderId", kiteOrder.orderId);

        } catch (KiteException e) {
            logger.error("Kite API error for {}: [{}] {}", order.getTradingSymbol(), e.code, e.getMessage());
            result.put("success", false);
            result.put("error", "Kite error " + e.code + ": " + e.getMessage());
        } catch (IOException e) {
            logger.error("Network error placing order for {}: {}", order.getTradingSymbol(), e.getMessage());
            result.put("success", false);
            result.put("error", "Network error: " + e.getMessage());
        }
        return result;
    }

    private String mapOrderType(String orderType) {
        if (orderType == null) return Constants.ORDER_TYPE_MARKET;
        return switch (orderType) {
            case "LIMIT" -> Constants.ORDER_TYPE_LIMIT;
            case "SL"    -> Constants.ORDER_TYPE_SL;
            case "SL-M"  -> Constants.ORDER_TYPE_SLM;
            default      -> Constants.ORDER_TYPE_MARKET;
        };
    }

    private String mapProductType(String productType) {
        if (productType == null) return Constants.PRODUCT_MIS;
        return switch (productType) {
            case "NRML" -> Constants.PRODUCT_NRML;
            case "CNC"  -> Constants.PRODUCT_CNC;
            default     -> Constants.PRODUCT_MIS;
        };
    }

    private void sendEntryNotification(InternalOrderBlock iob, AutoTradeOrder order) {
        try {
            String direction = "LONG".equals(iob.getTradeDirection()) ? "🟢 LONG" : "🔴 SHORT";
            String message = String.format(
                    "🤖 *AUTO TRADE ENTRY*\n" +
                    "%s %s\n" +
                    "Entry: ₹%.2f\n" +
                    "Qty: %d\n" +
                    "SL: ₹%.2f\n" +
                    "Target: ₹%.2f",
                    direction, iob.getInstrumentName(),
                    order.getAveragePrice(),
                    order.getFilledQuantity(),
                    iob.getStopLoss(),
                    iob.getTarget2()
            );

            telegramService.sendSystemAlert("Auto Trade Entry", message);
        } catch (Exception e) {
            logger.error("Error sending entry notification: {}", e.getMessage());
        }
    }

    private void sendExitNotification(Map<String, Object> position, Double exitPrice, String reason) {
        try {
            String direction = "LONG".equals(position.get("direction")) ? "🟢 LONG" : "🔴 SHORT";
            Double entryPrice = (Double) position.get("entryPrice");
            boolean isLong = "LONG".equals(position.get("direction"));
            double pnlPoints = isLong ? exitPrice - entryPrice : entryPrice - exitPrice;
            String pnlEmoji = pnlPoints > 0 ? "✅" : "❌";

            String message = String.format(
                    "🤖 *AUTO TRADE EXIT*\n" +
                    "%s %s\n" +
                    "Exit: ₹%.2f\n" +
                    "Reason: %s\n" +
                    "P&L: %s %.2f points",
                    direction, position.get("instrumentName"),
                    exitPrice,
                    reason,
                    pnlEmoji, pnlPoints
            );

            telegramService.sendSystemAlert("Auto Trade Exit", message);
        } catch (Exception e) {
            logger.error("Error sending exit notification: {}", e.getMessage());
        }
    }

    private void logActivity(String type, String message, Map<String, Object> data) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("timestamp", LocalDateTime.now(IST_ZONE_ID));
        entry.put("type", type);
        entry.put("message", message);
        entry.put("data", data);

        activityLog.add(entry);

        // Trim log if too large
        if (activityLog.size() > MAX_ACTIVITY_LOG_SIZE) {
            activityLog.remove(0);
        }
    }

    private Map<String, Object> convertConfigToMap(AutoTradingConfig config) {
        Map<String, Object> map = new HashMap<>();
        map.put("autoTradingEnabled", config.getAutoTradingEnabled());
        map.put("paperTradingMode", config.getPaperTradingMode());
        map.put("entryType", config.getEntryType());
        map.put("minConfidence", config.getMinConfidence());
        map.put("requireFvg", config.getRequireFvg());
        map.put("requireTrendAlignment", config.getRequireTrendAlignment());
        map.put("maxPositionSize", config.getMaxPositionSize());
        map.put("maxOpenPositions", config.getMaxOpenPositions());
        map.put("maxDailyTrades", config.getMaxDailyTrades());
        map.put("maxDailyLoss", config.getMaxDailyLoss());
        map.put("enableTrailingSL", config.getEnableTrailingSL());
        map.put("trailingSLTrigger", config.getTrailingSLTrigger());
        map.put("bookPartialProfits", config.getBookPartialProfits());
        map.put("partialProfitPercent", config.getPartialProfitPercent());
        map.put("defaultExitTarget", config.getDefaultExitTarget());
        map.put("exitAtMarketClose", config.getExitAtMarketClose());
        map.put("tradeStartTime", config.getTradeStartTime());
        map.put("tradeEndTime", config.getTradeEndTime());
        map.put("enabledInstruments", config.getEnabledInstruments());
        return map;
    }

    private Map<String, Object> convertOrderToMap(AutoTradeOrder order) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", order.getId());
        map.put("orderId", order.getOrderId());
        map.put("iobId", order.getIobId());
        map.put("instrumentToken", order.getInstrumentToken());
        map.put("instrumentName", order.getInstrumentName());
        map.put("orderType", order.getOrderType());
        map.put("transactionType", order.getTransactionType());
        map.put("quantity", order.getQuantity());
        map.put("price", order.getPrice());
        map.put("filledQuantity", order.getFilledQuantity());
        map.put("averagePrice", order.getAveragePrice());
        map.put("status", order.getStatus());
        map.put("orderPurpose", order.getOrderPurpose());
        map.put("orderTime", order.getOrderTime());
        map.put("fillTime", order.getFillTime());
        return map;
    }
}
