package com.trading.kalyani.KPN.service.serviceImpl;

import com.trading.kalyani.KPN.entity.InternalOrderBlock;
import com.trading.kalyani.KPN.repository.InternalOrderBlockRepository;
import com.trading.kalyani.KPN.model.HistoricalDataRequest;
import com.trading.kalyani.KPN.model.HistoricalDataResponse;
import com.trading.kalyani.KPN.model.HistoricalDataResponse.HistoricalCandle;
import com.trading.kalyani.KPN.service.AutoTradingService;
import com.trading.kalyani.KPN.service.InstrumentService;
import com.trading.kalyani.KPN.service.InternalOrderBlockService;
import com.trading.kalyani.KPN.service.RealTimePriceService;
import com.trading.kalyani.KPN.service.TelegramNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.*;
import java.util.function.Consumer;

import static com.trading.kalyani.KPN.constants.ApplicationConstants.*;

/**
 * Implementation of RealTimePriceService for managing live price feeds.
 * Integrates with Kite WebSocket for real-time tick data.
 */
@Service
public class RealTimePriceServiceImpl implements RealTimePriceService {

    private static final Logger logger = LoggerFactory.getLogger(RealTimePriceServiceImpl.class);

    private static final ZoneId IST_ZONE_ID = ZoneId.of("Asia/Kolkata");

    @Autowired
    private InternalOrderBlockRepository iobRepository;

    @Autowired(required = false)
    private InstrumentService instrumentService;

    @Autowired(required = false)
    private InternalOrderBlockService iobService;

    @Autowired(required = false)
    private AutoTradingService autoTradingService;

    @Autowired(required = false)
    private TelegramNotificationService telegramService;

    // Connection state
    private volatile boolean connected = false;
    private volatile LocalDateTime lastConnectionTime;
    private volatile LocalDateTime lastTickTime;
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private volatile boolean reconnectInProgress = false;
    // True once real Kite WebSocket is connected — disables the polling fallback automatically
    private volatile boolean webSocketActive = false;

    // Subscribed instruments
    private final Set<Long> subscribedInstruments = ConcurrentHashMap.newKeySet();

    // Current prices
    private final Map<Long, Double> currentPrices = new ConcurrentHashMap<>();

    // Last tick data
    private final Map<Long, Map<String, Object>> lastTicks = new ConcurrentHashMap<>();

    // Tick history (limited to last 100 ticks per instrument).
    // ConcurrentHashMap guards map-level ops; all deque mutations and reads are done
    // under synchronized(history) blocks, so no additional wrapper is needed.
    private final Map<Long, Deque<Map<String, Object>>> tickHistory = new ConcurrentHashMap<>();
    private static final int MAX_TICK_HISTORY = 100;

    // Current candle data
    private final Map<String, Map<String, Object>> currentCandles = new ConcurrentHashMap<>();

    // Price listeners
    private final Map<Long, List<Consumer<Map<String, Object>>>> priceListeners = new ConcurrentHashMap<>();

    // Zone touch alert callbacks
    private final Map<Long, Consumer<Map<String, Object>>> zoneTouchCallbacks = new ConcurrentHashMap<>();

    // Short-lived cache for fresh IOBs — avoids a DB hit on every tick.
    // Refreshed at most once every FRESH_IOB_CACHE_TTL_MS milliseconds per instrument.
    private static final long FRESH_IOB_CACHE_TTL_MS = 30_000; // 30 seconds
    private final Map<Long, List<InternalOrderBlock>> freshIOBCache = new ConcurrentHashMap<>();
    private final Map<Long, Long> freshIOBCacheTimestamp = new ConcurrentHashMap<>();

    /** Returns cached fresh IOBs, reloading from DB if the cache is older than TTL. */
    private List<InternalOrderBlock> getCachedFreshIOBs(Long instrumentToken) {
        long now = System.currentTimeMillis();
        Long cachedAt = freshIOBCacheTimestamp.get(instrumentToken);
        if (cachedAt == null || (now - cachedAt) > FRESH_IOB_CACHE_TTL_MS) {
            freshIOBCache.put(instrumentToken, iobRepository.findFreshIOBs(instrumentToken));
            freshIOBCacheTimestamp.put(instrumentToken, now);
        }
        return freshIOBCache.getOrDefault(instrumentToken, Collections.emptyList());
    }

    /** Call this when an IOB status changes so the next tick picks up the updated list. */
    public void invalidateFreshIOBCache(Long instrumentToken) {
        freshIOBCache.remove(instrumentToken);
        freshIOBCacheTimestamp.remove(instrumentToken);
    }

    // Executor for heartbeat and price polling
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> pricePollingTask;

    // Dedicated executor for listener dispatch — keeps tick-processing thread free
    // from slow listeners (DB calls, HTTP requests, Telegram alerts, etc.)
    private final ExecutorService listenerExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "price-listener-dispatch");
        t.setDaemon(true);
        return t;
    });

    // ==================== Connection Management ====================

    @Override
    public void connect() {
        if (connected) {
            logger.info("Already connected to price feed");
            return;
        }

        try {
            logger.info("Connecting to real-time price feed...");

            // Initialize default subscriptions
            subscribedInstruments.add(NIFTY_INSTRUMENT_TOKEN);

            // Start heartbeat
            startHeartbeat();

            // Start price polling (fallback if WebSocket not available)
            startPricePolling();

            connected = true;
            lastConnectionTime = LocalDateTime.now(IST_ZONE_ID);
            reconnectAttempts.set(0);

            logger.info("Connected to real-time price feed. Subscribed instruments: {}",
                    subscribedInstruments.size());

            // TODO: Integrate with Kite WebSocket
            // KiteConnect kite = kiteService.getKiteConnect();
            // KiteTicker ticker = new KiteTicker(kite.getAccessToken(), kite.getApiKey());
            // ticker.setOnTickerArrivalListener(this::handleTick);
            // ticker.connect();
            // ticker.subscribe(subscribedInstruments.stream().mapToLong(Long::longValue).toArray());

        } catch (Exception e) {
            logger.error("Error connecting to price feed: {}", e.getMessage(), e);
            connected = false;
            scheduleReconnect();
        }
    }

    @Override
    public void disconnect() {
        logger.info("Disconnecting from real-time price feed...");

        connected = false;

        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
        if (pricePollingTask != null) {
            pricePollingTask.cancel(false);
        }

        // TODO: Disconnect Kite WebSocket
        // ticker.disconnect();

        logger.info("Disconnected from real-time price feed");
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public Map<String, Object> getConnectionStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("connected", connected);
        status.put("lastConnectionTime", lastConnectionTime);
        status.put("lastTickTime", lastTickTime);
        status.put("subscribedInstruments", subscribedInstruments.size());
        status.put("reconnectAttempts", reconnectAttempts.get());
        status.put("activeListeners", priceListeners.values().stream()
                .mapToInt(List::size).sum());
        return status;
    }

    private void startHeartbeat() {
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            if (!connected && !reconnectInProgress) {
                scheduleReconnect();
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    private void startPricePolling() {
        // Fallback REST polling — only active when Kite WebSocket is not connected.
        // Initial delay of 5s ensures connected=true is set before first execution.
        pricePollingTask = scheduler.scheduleAtFixedRate(() -> {
            if (!connected || webSocketActive) return;
            try {
                pollPrices();
            } catch (Exception e) {
                // Catch all exceptions — scheduleAtFixedRate silently kills the task on uncaught throws
                logger.error("Price polling error (task still running): {}", e.getMessage(), e);
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    /**
     * Call this when the Kite WebSocket connection is established.
     * Disables the REST polling fallback so both don't run simultaneously.
     */
    public void onWebSocketConnected() {
        webSocketActive = true;
        logger.info("WebSocket active — REST price polling suppressed");
    }

    /**
     * Call this when the Kite WebSocket disconnects.
     * Re-enables REST polling until WebSocket recovers.
     */
    public void onWebSocketDisconnected() {
        webSocketActive = false;
        logger.warn("WebSocket disconnected — falling back to REST price polling");
    }

    private void scheduleReconnect() {
        if (reconnectAttempts.get() >= 5) {
            logger.error("Max reconnect attempts reached. Manual intervention required.");
            return;
        }

        reconnectInProgress = true;
        int attempt = reconnectAttempts.incrementAndGet();
        int delay = Math.min(30, attempt * 5);

        // Cancel the stale polling task so reconnect doesn't accumulate duplicate loops
        if (pricePollingTask != null && !pricePollingTask.isCancelled()) {
            pricePollingTask.cancel(false);
        }

        logger.info("Scheduling reconnect #{} in {}s", attempt, delay);
        scheduler.schedule(() -> {
            try {
                logger.info("Attempting reconnect #{}", attempt);
                connect();
            } finally {
                reconnectInProgress = false;
            }
        }, delay, TimeUnit.SECONDS);
    }

    private void pollPrices() {
        // TODO: Replace with real Kite Quote API fetch when WebSocket is not available
        // e.g. kiteService.getQuote(subscribedInstruments) → handlePriceUpdate(token, ltp)
        // Do NOT simulate random prices here — fake prices trigger real auto-trade and mitigation logic.
        logger.debug("Price polling active but no real data source configured — skipping tick.");
    }

    // ==================== Subscription Management ====================

    @Override
    public void subscribeToInstruments(List<Long> instrumentTokens) {
        subscribedInstruments.addAll(instrumentTokens);
        logger.info("Subscribed to instruments: {}", instrumentTokens);

        // TODO: Subscribe via Kite WebSocket
        // ticker.subscribe(instrumentTokens.stream().mapToLong(Long::longValue).toArray());
    }

    @Override
    public void unsubscribeFromInstruments(List<Long> instrumentTokens) {
        subscribedInstruments.removeAll(new HashSet<>(instrumentTokens));
        instrumentTokens.forEach(token -> {
            currentPrices.remove(token);
            lastTicks.remove(token);
            tickHistory.remove(token);
            priceListeners.remove(token);
        });
        logger.info("Unsubscribed from instruments: {}", instrumentTokens);

        // TODO: Unsubscribe via Kite WebSocket
        // ticker.unsubscribe(instrumentTokens.stream().mapToLong(Long::longValue).toArray());
    }

    @Override
    public List<Long> getSubscribedInstruments() {
        return new ArrayList<>(subscribedInstruments);
    }

    @Override
    public void subscribeToZoneTouchAlerts(Long iobId, Consumer<Map<String, Object>> callback) {
        zoneTouchCallbacks.put(iobId, callback);
        logger.debug("Subscribed to zone touch alerts for IOB: {}", iobId);
    }

    @Override
    public void unsubscribeFromZoneTouchAlerts(Long iobId) {
        zoneTouchCallbacks.remove(iobId);
    }

    // ==================== Price Data ====================

    @Override
    public Double getCurrentPrice(Long instrumentToken) {
        return currentPrices.get(instrumentToken);
    }

    @Override
    public Map<Long, Double> getAllCurrentPrices() {
        return new HashMap<>(currentPrices);
    }

    @Override
    public Map<String, Object> getLastTick(Long instrumentToken) {
        Map<String, Object> tick = lastTicks.get(instrumentToken);
        return tick != null ? new HashMap<>(tick) : new HashMap<>();
    }

    @Override
    public List<Map<String, Object>> getTickHistory(Long instrumentToken, int count) {
        Deque<Map<String, Object>> history = tickHistory.get(instrumentToken);
        if (history == null) {
            return new ArrayList<>();
        }
        // Hold the lock for the entire snapshot — prevents size() changing between
        // the isEmpty check and the toArray copy, and avoids ConcurrentModificationException.
        synchronized (history) {
            if (history.isEmpty()) return new ArrayList<>();
            int skip = Math.max(0, history.size() - count);
            return history.stream().skip(skip).collect(java.util.stream.Collectors.toList());
        }
    }

    // ==================== OHLC Data ====================

    @Override
    public Map<String, Object> getCurrentCandle(Long instrumentToken, String timeframe) {
        String key = instrumentToken + "_" + timeframe;
        Map<String, Object> candle = currentCandles.get(key);
        if (candle == null) return new HashMap<>();
        // Synchronize on the candle — updateCurrentCandle mutates it under the same lock
        synchronized (candle) {
            return new HashMap<>(candle);
        }
    }

    @Override
    public List<Map<String, Object>> getOHLCData(Long instrumentToken, String timeframe, int count) {
        if (instrumentService == null) {
            logger.warn("InstrumentService not available — cannot fetch OHLC data");
            return new ArrayList<>();
        }

        String kiteInterval;
        int lookbackDays;
        switch (timeframe) {
            case "1min":   kiteInterval = "minute";    lookbackDays = 1;   break;
            case "5min":   kiteInterval = "5minute";   lookbackDays = 5;   break;
            case "15min":  kiteInterval = "15minute";  lookbackDays = 10;  break;
            case "1hour":  kiteInterval = "60minute";  lookbackDays = 30;  break;
            case "daily":  kiteInterval = "day";       lookbackDays = 180; break;
            default:
                logger.warn("Unknown timeframe '{}' for OHLC fetch — defaulting to 5minute", timeframe);
                kiteInterval = "5minute";
                lookbackDays = 5;
        }

        try {
            LocalDateTime toDate   = LocalDateTime.now(IST_ZONE_ID);
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
            if (response == null || !response.isSuccess() || response.getCandles() == null) {
                logger.warn("No OHLC data returned for token={} timeframe={}", instrumentToken, timeframe);
                return new ArrayList<>();
            }

            List<HistoricalCandle> candles = response.getCandles();
            // Return the last `count` candles as Maps
            int skip = Math.max(0, candles.size() - count);
            List<Map<String, Object>> result = new ArrayList<>();
            for (int i = skip; i < candles.size(); i++) {
                HistoricalCandle c = candles.get(i);
                Map<String, Object> ohlc = new LinkedHashMap<>();
                ohlc.put("timestamp", c.getTimestamp());
                ohlc.put("open",   c.getOpen());
                ohlc.put("high",   c.getHigh());
                ohlc.put("low",    c.getLow());
                ohlc.put("close",  c.getClose());
                ohlc.put("volume", c.getVolume());
                result.add(ohlc);
            }
            return result;

        } catch (Exception e) {
            logger.error("Error fetching OHLC data for token={} timeframe={}: {}", instrumentToken, timeframe, e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    // ==================== Price Listeners ====================

    @Override
    public void addPriceListener(Long instrumentToken, Consumer<Map<String, Object>> listener) {
        if (listener == null) {
            logger.warn("Attempted to add null price listener for token {}", instrumentToken);
            return;
        }
        priceListeners.computeIfAbsent(instrumentToken, k -> new CopyOnWriteArrayList<>())
                .add(listener);
    }

    /**
     * Removes a price listener by object identity (==), not equals().
     * Callers MUST hold the same Consumer reference that was passed to addPriceListener.
     * Passing a new lambda instance will NOT remove the original — store the reference:
     * <pre>
     *   Consumer&lt;Map&lt;String,Object&gt;&gt; myListener = tick -> process(tick);
     *   priceService.addPriceListener(token, myListener);
     *   // later:
     *   priceService.removePriceListener(token, myListener);
     * </pre>
     */
    @Override
    public void removePriceListener(Long instrumentToken, Consumer<Map<String, Object>> listener) {
        List<Consumer<Map<String, Object>>> listeners = priceListeners.get(instrumentToken);
        if (listeners == null) return;
        // removeIf with == (identity) — equals() on lambdas is unreliable
        listeners.removeIf(l -> l == listener);
        // Clean up the map entry when the list becomes empty to avoid memory leak
        if (listeners.isEmpty()) {
            priceListeners.remove(instrumentToken, listeners);
        }
    }

    @Override
    public void broadcastPriceUpdate(Long instrumentToken, Map<String, Object> tickData) {
        List<Consumer<Map<String, Object>>> listeners = priceListeners.get(instrumentToken);
        if (listeners == null || listeners.isEmpty()) return;

        // Snapshot the tick data once — each listener gets its own copy so a mutating
        // listener cannot corrupt the map seen by subsequent listeners in the same broadcast.
        Map<String, Object> snapshot = new HashMap<>(tickData);

        for (Consumer<Map<String, Object>> listener : listeners) {
            listenerExecutor.submit(() -> {
                try {
                    listener.accept(snapshot);
                } catch (Exception e) {
                    // Include 'e' so the full stack trace is written, not just the message
                    logger.error("Error in price listener for token {}: {}", instrumentToken, e.getMessage(), e);
                }
            });
        }
    }

    // ==================== IOB Integration ====================

    @Override
    public void checkIOBZonesTouch(Long instrumentToken, Double currentPrice) {
        if (currentPrice == null) return;

        // Use cache — avoids a DB hit on every tick (called every 5 seconds per instrument)
        List<InternalOrderBlock> freshIOBs = getCachedFreshIOBs(instrumentToken);
        if (freshIOBs.isEmpty()) return;

        boolean anyZoneTouched = false;

        for (InternalOrderBlock iob : freshIOBs) {
            boolean inZone = currentPrice >= iob.getZoneLow() && currentPrice <= iob.getZoneHigh();
            if (!inZone) continue;

            anyZoneTouched = true;

            // Notify zone touch callback off the tick thread — slow callbacks must not block price updates
            Consumer<Map<String, Object>> callback = zoneTouchCallbacks.get(iob.getId());
            if (callback != null) {
                Map<String, Object> alert = new HashMap<>();
                alert.put("iobId", iob.getId());
                alert.put("instrumentToken", instrumentToken);
                alert.put("currentPrice", currentPrice);
                alert.put("zoneHigh", iob.getZoneHigh());
                alert.put("zoneLow", iob.getZoneLow());
                alert.put("direction", iob.getTradeDirection());
                alert.put("timestamp", LocalDateTime.now(IST_ZONE_ID));
                listenerExecutor.submit(() -> callback.accept(alert));
            }

            // Auto trade: check conditions and execute if valid
            // IOBScheduler.processAutoTrades() also runs every minute — this path fires
            // immediately on zone touch for lower-latency entry.
            if (autoTradingService != null && autoTradingService.isAutoTradingEnabled()) {
                Map<String, Object> conditions = autoTradingService.checkEntryConditions(
                        iob.getId(), currentPrice);
                if (Boolean.TRUE.equals(conditions.get("valid"))) {
                    logger.info("Zone touch: IOB {} entry conditions met — executing auto trade", iob.getId());
                    listenerExecutor.submit(() -> {
                        try {
                            autoTradingService.placeIOBOrder(iob.getId(), currentPrice, null);
                        } catch (Exception e) {
                            logger.error("Auto trade execution failed for IOB {}: {}", iob.getId(), e.getMessage(), e);
                        }
                    });
                }
            }
        }

        // checkMitigation is instrument-level — call once outside the loop, not once per IOB.
        // Only call when a zone was touched to avoid redundant DB checks on every tick.
        // The scheduler's per-minute mitigation check handles all other cases.
        if (anyZoneTouched && iobService != null) {
            iobService.checkMitigation(instrumentToken, currentPrice);
        }
    }

    @Override
    public Map<String, Object> getDistanceToNearestZone(Long instrumentToken, Double currentPrice) {
        Map<String, Object> result = new HashMap<>();

        if (currentPrice == null) {
            result.put("error", "No current price");
            return result;
        }

        List<InternalOrderBlock> freshIOBs = getCachedFreshIOBs(instrumentToken);

        if (freshIOBs.isEmpty()) {
            result.put("hasNearbyZone", false);
            return result;
        }

        InternalOrderBlock nearestIOB = null;
        double minDistance = Double.MAX_VALUE;

        for (InternalOrderBlock iob : freshIOBs) {
            double distance;
            if (currentPrice > iob.getZoneHigh()) {
                distance = currentPrice - iob.getZoneHigh();
            } else if (currentPrice < iob.getZoneLow()) {
                distance = iob.getZoneLow() - currentPrice;
            } else {
                distance = 0; // Price is in zone
            }

            if (distance < minDistance) {
                minDistance = distance;
                nearestIOB = iob;
            }
        }

        if (nearestIOB != null) {
            result.put("hasNearbyZone", true);
            result.put("iobId", nearestIOB.getId());
            result.put("iobType", nearestIOB.getObType());
            result.put("zoneHigh", nearestIOB.getZoneHigh());
            result.put("zoneLow", nearestIOB.getZoneLow());
            result.put("distancePoints", minDistance);
            result.put("distancePercent", (minDistance / currentPrice) * 100);
            result.put("direction", nearestIOB.getTradeDirection());
            result.put("inZone", minDistance == 0);
        }

        return result;
    }

    // ==================== Tick Handler ====================

    /**
     * Handle incoming tick data from WebSocket or polling
     */
    private void handlePriceUpdate(Long instrumentToken, Double newPrice) {
        if (instrumentToken == null || newPrice == null) return;

        Double previousPrice = currentPrices.get(instrumentToken);
        currentPrices.put(instrumentToken, newPrice);
        lastTickTime = LocalDateTime.now(IST_ZONE_ID);

        // Create tick data
        Map<String, Object> tickData = new HashMap<>();
        tickData.put("instrumentToken", instrumentToken);
        tickData.put("lastPrice", newPrice);
        tickData.put("timestamp", lastTickTime);
        tickData.put("previousPrice", previousPrice);
        tickData.put("change", previousPrice != null ? newPrice - previousPrice : 0);
        tickData.put("changePercent", previousPrice != null && previousPrice > 0 ?
                ((newPrice - previousPrice) / previousPrice) * 100 : 0);

        // Store last tick
        lastTicks.put(instrumentToken, tickData);

        // Add to history — computeIfAbsent is atomic at map level; synchronize on the deque
        // for the compound add+trim so a concurrent getTickHistory cannot see a torn state.
        Deque<Map<String, Object>> history =
                tickHistory.computeIfAbsent(instrumentToken, k -> new ArrayDeque<>());
        synchronized (history) {
            history.addLast(tickData);
            if (history.size() > MAX_TICK_HISTORY) {
                history.removeFirst();
            }
        }

        // Update current candle
        updateCurrentCandle(instrumentToken, newPrice);

        // Broadcast to listeners
        broadcastPriceUpdate(instrumentToken, tickData);

        // Check IOB zones
        checkIOBZonesTouch(instrumentToken, newPrice);
    }

    private void updateCurrentCandle(Long instrumentToken, Double price) {
        String key = instrumentToken + "_1min";
        Map<String, Object> candle = currentCandles.computeIfAbsent(key, k -> {
            Map<String, Object> newCandle = new ConcurrentHashMap<>();
            newCandle.put("open", price);
            newCandle.put("high", price);
            newCandle.put("low", price);
            newCandle.put("close", price);
            newCandle.put("startTime", LocalDateTime.now(IST_ZONE_ID));
            return newCandle;
        });

        // Synchronize on the candle to make the read-modify-write on high/low atomic
        synchronized (candle) {
            candle.put("close", price);
            candle.put("high", Math.max((Double) candle.get("high"), price));
            candle.put("low", Math.min((Double) candle.get("low"), price));
        }
    }

    /**
     * Handle tick from Kite WebSocket (to be called when integrated)
     */
    public void handleKiteTick(Object tick) {
        // TODO: Parse Kite tick object
        // long instrumentToken = tick.getInstrumentToken();
        // double lastPrice = tick.getLastTradedPrice();
        // handlePriceUpdate(instrumentToken, lastPrice);
    }

    /**
     * Initialize with seed prices (useful for testing)
     */
    public void seedPrices(Map<Long, Double> prices) {
        // Only populate the price cache — do NOT push through handlePriceUpdate,
        // which would trigger zone checks, auto-trade conditions, and mitigation on seed data.
        currentPrices.putAll(prices);
        logger.info("Seeded {} instrument prices (no events fired)", prices.size());
    }

    @PreDestroy
    public void shutdown() {
        disconnect();
        scheduler.shutdown();
        listenerExecutor.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            if (!listenerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                listenerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            listenerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("RealTimePriceService executors shut down");
    }
}
