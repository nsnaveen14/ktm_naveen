package com.trading.kalyani.KPN.service;

import com.trading.kalyani.KPN.entity.IndexLTP;
import com.trading.kalyani.KPN.entity.NiftyLTP;
import com.trading.kalyani.KPN.model.AutoTradeParams;
import com.zerodhatech.models.Instrument;
import com.zerodhatech.models.User;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface AsyncService {

    CompletableFuture<Void> saveUserNInstrumentDataAsync(User userModel, List<Instrument> instruments);

    CompletableFuture<Void> sendEmailToAllUsersAsync(String subject, String body, String attachmentPath);

    CompletableFuture<Void> placeOrderAsync(IndexLTP indexLTP, AutoTradeParams autoTradeParams);

    CompletableFuture<Void> closeOrdersAsync(IndexLTP indexLTP);


}
