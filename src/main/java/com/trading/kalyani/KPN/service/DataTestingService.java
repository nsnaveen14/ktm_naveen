package com.trading.kalyani.KPN.service;

import com.trading.kalyani.KPN.entity.IndexLTP;
import com.trading.kalyani.KPN.entity.TradeDecisions;
import com.trading.kalyani.KPN.model.CommonReqRes;
import com.trading.kalyani.KPN.model.KiteModel;

import java.time.LocalDateTime;
import java.util.List;

public interface DataTestingService {

    CommonReqRes getPowerTradeDeltaTable(LocalDateTime reqLocalDateTime);

    List<IndexLTP> getNiftyLTPTestDataFromTable();

    CommonReqRes testCalculateAutoTradeLotSizes(KiteModel kiteModel);

    List<IndexLTP> testIndexLTPMessage();

    TradeDecisions testTradeDecisionMessage(Integer appJobConfigNum);

    CommonReqRes testEmailSending(Integer appJobConfigNum);
}
