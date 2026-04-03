package com.trading.kalyani.KPN.service;

import java.util.Map;

public interface EMACrossoverSignalService {

    /**
     * Checks for an EMA crossover breakout signal using a two-stage confirmation filter.
     *
     * Stage A: fresh EMA crossover arms the recent swing high/low as a pending breakout level.
     * Stage B: when live LTP crosses that level, the signal fires (FVG nearby → STRONG).
     *
     * @return map with keys: hasSignal (Boolean), signalType ("BUY"/"SELL"),
     *         signalStrength ("STRONG"/"MODERATE"), emaAlignment (String),
     *         ema9 (Double), ema21 (Double), breakoutLevel (Double),
     *         fvgConfirmed (Boolean), fvgMidpoint (Double, optional)
     */
    Map<String, Object> checkSignal();

    /**
     * Clears the Stage A/B pending state after a trade has been successfully placed.
     * Must be called from the placement path (processNewSignals) only — never from
     * read-only UI polling paths.
     */
    void clearPendingState();
}
