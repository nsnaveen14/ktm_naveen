package com.trading.kalyani.KPN.service;

import java.util.Map;

public interface GainzAlgoSignalService {

    /**
     * Checks for a GainzAlgo v2 Alpha pullback-resumption signal.
     * Detects entries when price pulls back within an established EMA trend and RSI momentum
     * pivots back in the trend direction (RSI crosses 48 up = BUY; RSI crosses 52 down = SELL).
     *
     * @return map with keys: hasSignal (Boolean), signalType ("BUY"/"SELL"),
     *         signalStrength ("STRONG"/"MODERATE"), ema9 (Double), ema21 (Double), rsi (Double)
     */
    Map<String, Object> checkSignal();
}
