package com.trading.kalyani.KPN.service;

import com.trading.kalyani.KPN.entity.OptionBuyingConfig;
import com.trading.kalyani.KPN.entity.SimulatedTrade;

import java.util.List;
import java.util.Map;

/**
 * Service for the standalone Option Buying Strategy module.
 *
 * Aggregates signals from IOB, Brahmastra, GainzAlgo, and ZeroHero,
 * then buys ATM CE (BUY signal) or ATM PE (SELL signal) with its own
 * independent risk management configuration.
 */
public interface OptionBuyingService {

    /** Load or initialise the single config row. */
    OptionBuyingConfig getConfig();

    /** Persist updated configuration. */
    OptionBuyingConfig updateConfig(OptionBuyingConfig config);

    /** Enable the strategy. */
    void enable();

    /** Disable the strategy (does not exit open trades). */
    void disable();

    /**
     * Status snapshot: enabled flag, open trade count, today's P&L, today's trade count.
     */
    Map<String, Object> getStatus();

    /**
     * Called by the scheduler every minute.
     * Checks all enabled signal sources and places trades when conditions are met.
     */
    void checkAndExecute();

    /**
     * Called by the scheduler every minute.
     * Monitors open OPT_BUY trades and exits on target / SL / time.
     */
    void monitorOpenTrades();

    /** All currently open OPT_BUY trades. */
    List<SimulatedTrade> getOpenTrades();

    /** All OPT_BUY trades placed today (open + closed). */
    List<SimulatedTrade> getTodayTrades();
}
