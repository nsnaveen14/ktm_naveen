package com.trading.kalyani.KPN.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Configuration for the standalone Option Buying Strategy module.
 * Single-row table (id = 1), updated in-place.
 */
@Entity
@Table(name = "option_buying_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OptionBuyingConfig {

    @Id
    private Long id = 1L;

    // ── Master switch ───────────────────────────────────────────────────────
    @Column(name = "enabled")
    private boolean enabled = false;

    // ── Per-source switches ─────────────────────────────────────────────────
    @Column(name = "enable_iob")
    private boolean enableIob = true;

    @Column(name = "enable_brahmastra")
    private boolean enableBrahmastra = true;

    @Column(name = "enable_gainz_algo")
    private boolean enableGainzAlgo = true;

    @Column(name = "enable_zero_hero")
    private boolean enableZeroHero = true;

    // ── Position sizing ─────────────────────────────────────────────────────
    @Column(name = "num_lots")
    private int numLots = 2;

    // ── Risk management ─────────────────────────────────────────────────────
    @Column(name = "target_percent")
    private double targetPercent = 30.0;

    @Column(name = "stoploss_percent")
    private double stoplossPercent = 15.0;

    @Column(name = "max_open_trades")
    private int maxOpenTrades = 3;

    @Column(name = "max_daily_loss")
    private double maxDailyLoss = -5000.0;

    @Column(name = "max_daily_trades")
    private int maxDailyTrades = 10;

    // ── Time window (HH:mm) ─────────────────────────────────────────────────
    @Column(name = "trade_start_time")
    private String tradeStartTime = "09:20";

    @Column(name = "trade_end_time")
    private String tradeEndTime = "15:00";

    // ── Signal filtering ────────────────────────────────────────────────────
    @Column(name = "min_signal_strength")
    private String minSignalStrength = "MODERATE"; // STRONG / MODERATE / WEAK

    @Column(name = "exit_on_reverse_signal")
    private boolean exitOnReverseSignal = true;

    // ══════════════════════════════════════════════════════════════════════════
    // OPTIMIZATION 1 — Signal Confluence Filter
    // Only trade when N+ sources agree on same direction.
    // Applies to IOB, Brahmastra, GainzAlgo (not ZeroHero — it is standalone).
    // Boxed types so Hibernate can load NULL from pre-existing DB rows without error.
    // ══════════════════════════════════════════════════════════════════════════

    @Column(name = "require_confluence")
    private Boolean requireConfluence = false;

    /** Minimum number of sources that must agree (2 = any two of IOB/Brahmastra/GainzAlgo). */
    @Column(name = "min_confluence_count")
    private Integer minConfluenceCount = 2;

    // ══════════════════════════════════════════════════════════════════════════
    // OPTIMIZATION 2 — VIX / Premium Filter
    // ══════════════════════════════════════════════════════════════════════════

    /** Skip entry when India VIX is above this level. 0 = disabled. */
    @Column(name = "max_vix")
    private Double maxVix = 20.0;

    /** Minimum option premium to enter (avoids illiquid/worthless options). */
    @Column(name = "min_premium")
    private Double minPremium = 10.0;

    /** Maximum option premium to enter (avoids overpriced options with poor R:R). */
    @Column(name = "max_premium")
    private Double maxPremium = 300.0;

    // ══════════════════════════════════════════════════════════════════════════
    // OPTIMIZATION 3 — Trailing Stop Loss
    // ══════════════════════════════════════════════════════════════════════════

    @Column(name = "trailing_sl_enabled")
    private Boolean trailingSlEnabled = false;

    /**
     * Percentage of distance to target at which trailing SL activates.
     * e.g. 50 = trailing SL kicks in once 50% of the move to target is covered.
     */
    @Column(name = "trailing_activation_pct")
    private Double trailingActivationPct = 50.0;

    /**
     * Percentage of profit to trail the SL at.
     * e.g. 50 = trailing SL locked at entry + 50% of current profit.
     */
    @Column(name = "trailing_trail_pct")
    private Double trailingTrailPct = 50.0;
}
