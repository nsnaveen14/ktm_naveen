package com.trading.kalyani.KPN.service;

import java.util.Map;

/**
 * Zero Hero Signal Service — expiry day afternoon strategy.
 *
 * "Zero Hero": Buy cheap OTM/ATM options (near-zero premium) after 2:30 PM on expiry day.
 * If NIFTY makes a directional move in the last 60 min, these options multiply 3x–10x.
 *
 * Entry window  : 2:30 PM – 3:10 PM (Tuesday expiry day only)
 * Premium range : 5 – 50 points (the "near zero" zone worth risking)
 * Target        : 3× entry premium
 * Stop Loss     : 50% of entry premium
 * Hard exit     : 3:25 PM regardless of P&L (avoid last-minute chaos)
 */
public interface ZeroHeroSignalService {

    /**
     * Check if a Zero Hero entry signal exists right now.
     * Returns a result map with:
     *   hasSignal       (Boolean)
     *   signalType      (String)  BUY / SELL
     *   optionType      (String)  CE / PE
     *   strikePrice     (Integer)
     *   optionPremium   (Double)  current LTP of the option
     *   targetPrice     (Double)  3× premium
     *   stopLossPrice   (Double)  50% of premium
     *   momentumScore   (Integer) 0–3 how many indicators agree
     *   signalStrength  (String)  STRONG / MODERATE
     */
    Map<String, Object> checkSignal(Map<String, Object> liveData);

    /**
     * Check signal with a configurable minimum momentum score.
     *
     * @param liveData live market data
     * @param minScore minimum number of momentum indicators (out of 3) that must agree (2 or 3)
     */
    Map<String, Object> checkSignal(Map<String, Object> liveData, int minScore);
}
