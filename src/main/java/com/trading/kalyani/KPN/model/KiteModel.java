package com.trading.kalyani.KPN.model;

import lombok.*;

import java.util.ArrayList;

@Getter
@Setter
@AllArgsConstructor
@ToString
@NoArgsConstructor
public class KiteModel {

    private ArrayList<Long> instrumentTokens;

    private ArrayList<Long> exchangeTokens;

}
