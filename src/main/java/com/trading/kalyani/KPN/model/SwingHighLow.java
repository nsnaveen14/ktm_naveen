package com.trading.kalyani.KPN.model;

import com.trading.kalyani.KPN.entity.CandleStick;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class SwingHighLow {

    CandleStick swingHigh;
    CandleStick swingLow;
}
