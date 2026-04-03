package com.trading.kalyani.KPN.service;

import com.trading.kalyani.KPN.dto.brahmastra.LiveScanResult;
import com.trading.kalyani.KPN.entity.*;
import com.trading.kalyani.KPN.model.CommonReqRes;
import com.trading.kalyani.KPN.model.IndexOHLC;
import com.trading.kalyani.KPN.model.Message;

import java.util.List;

public interface MessagingService {

    void sendChatMessage(Message message);

    void sendInstrumentMessage(Message message);

    void sendMiniDeltaMessage(List<MiniDelta> message);

    void sendNiftyLTPMessage(NiftyLTP message);

    void sendNiftyLTPValue(CommonReqRes message);

    void sendCommonMessage(CommonReqRes message);

    void sendPowerTradeDeltaOIMessage(PowerTradeDeltaOI powerTradeDeltaOI);

    void sendOHLCMessage(IndexOHLC message);

    void sendIndexLTPMessage(IndexLTP message);

    void sendTradeDecisionMessage(TradeDecisions lastTradeDecisions);

    void sendBrahmastraSignal(LiveScanResult signal);
}
