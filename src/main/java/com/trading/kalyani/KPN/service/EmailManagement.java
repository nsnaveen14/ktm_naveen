package com.trading.kalyani.KPN.service;

import com.trading.kalyani.KPN.entity.AppJobConfig;
import com.trading.kalyani.KPN.entity.IndexLTP;
import com.trading.kalyani.KPN.entity.JobIterationDetails;
import com.trading.kalyani.KPN.entity.TradeDecisions;

public interface EmailManagement {

    void sendEmailForErrorInJob(Exception e, AppJobConfig appJobConfig, Integer retryCounter, JobIterationDetails jobIterationDetails);

    void sendEmailAsync(String fileName, AppJobConfig appJobConfig);

    void sendEmailOfTradeDecision(IndexLTP indexLTP, AppJobConfig appJobConfig);

    void sendEmailOfAutoTradeDecision(IndexLTP indexLTP, AppJobConfig appJobConfig, TradeDecisions lastTradeDecisions);

    void sendEmailOfPlacingAutoTradeOrder(TradeDecisions lastTradeDecisions, String instrumentToken);

    void sendEmailForClosingTrade(IndexLTP indexLTP, AppJobConfig appJobConfig, TradeDecisions tradeDecisions);

    void sendEmailForJobStopped(Integer appJobConfigNum);

    void sendEmailForRangeChange(IndexLTP indexLTP, IndexLTP prevItrIndexLTP, AppJobConfig appJobConfig);
}
