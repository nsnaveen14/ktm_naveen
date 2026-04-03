package com.trading.kalyani.KPN.model;

import lombok.*;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class MaxPain {

    // Calculate loss at each strike and determine min
    Map<Integer, Long> lossMatrix;

    int maxPainSP;

    int maxPainSPSecond;

    float maxPainBiasRatio;

}
