
import { Injectable, OnDestroy,OnInit } from '@angular/core';
import {CompatClient, Stomp,StompSubscription} from '@stomp/stompjs';
import { Message } from '../models/message';
import { DataService } from './data.service';
import { CommonReqRes } from '../models/CommonReqRes';
import { IndexOHLC } from '../models/IndexOHLC';
import { IndexLTPModel } from '../models/indexLTPModel';
import { MiniDelta } from '../models/minidelta';
import { TradeDecisionModel } from '../models/TradeDecisionModel';
import { Subject, Observable } from 'rxjs';
import { PriceTick } from '../models/performance.model';
export type ListenerCallBack = (message: Message) => void;
export type CommonMessageListenerCallBack = (message: CommonReqRes) => void;
export type IndexOHLCListenerCallBack = (message: IndexOHLC) => void;
export type IndexLTPListenerCallBack = (message: IndexLTPModel) => void;
export type MiniDeltaListenerCallBack = (message: MiniDelta[]) => void;
export type TradeDecisionListenerCallBack = (message: TradeDecisionModel) => void;
export type PriceUpdateListenerCallBack = (message: PriceTick) => void;

export type IOBZoneTouchAlertListenerCallBack = (message: any) => void;
export type IOBMitigationAlertListenerCallBack = (message: any) => void;
export type IOBNewOrderListenerCallBack = (message: any) => void;

export type AutoTradeListenerCallBack = (message: any) => void;

export type BrahmastraSignalListenerCallBack = (message: any) => void;

@Injectable({
  providedIn: 'root'
})
export class WebsocketService implements OnDestroy,OnInit{

  private connection: CompatClient | undefined = undefined;

  private minideltasubscription: StompSubscription | undefined;

  private commonMessageSubscription: StompSubscription | undefined;

  private indexOHLCSubscription: StompSubscription | undefined;

  private indexLTPSubscription: StompSubscription | undefined;

  private tradeDecisionSubscription: StompSubscription | undefined;


  private priceUpdateSubscription: StompSubscription | undefined;

  private iobZoneTouchAlertSubscription: StompSubscription | undefined;

  private iobMitigationAlertSubscription: StompSubscription | undefined;

  private iobNewOrderSubscription: StompSubscription | undefined;

  private autoTradeSubscription: StompSubscription | undefined;

  private brahmastraSignalSubscription: StompSubscription | undefined;

  private optionBuyingSubscription: StompSubscription | undefined;

    // Price updates
  private priceUpdates$ = new Subject<PriceTick>();

  // IOB updates
  private iobUpdates$ = new Subject<any>();
  private zoneTouchAlerts$ = new Subject<any>();
  private mitigationAlerts$ = new Subject<any>();

  // Auto trade updates
  private autoTradeUpdates$ = new Subject<any>();

  // Brahmastra signal updates
  private brahmastraSignals$ = new Subject<any>();

  // Option Buying updates
  private optionBuyingUpdates$ = new Subject<any>();

  public kiteWSUrl: string | any;



  constructor(dataService: DataService) {

    this.connection = Stomp.client(dataService.webSocketUrl);

  // Establish the WebSocket connection once
  this.connection.connect({}, () => {
    console.log('WebSocket connection established in the constructor.');
  }, (error: any) => {
    console.error('WebSocket connection error:', error);
  });


  }

  public send(message: Message): void {
    if (this.connection && this.connection.connected) {
      this.connection.send('/app/chat', {}, JSON.stringify(message));
    }
  }



  public listenToMultiSegmentTopics(funIndexOHLC: IndexOHLCListenerCallBack,funCommonMessage: CommonMessageListenerCallBack,funIndexLTP: IndexLTPListenerCallBack,funMiniDelta: MiniDeltaListenerCallBack, funTradeDecision: TradeDecisionListenerCallBack
    , funPriceUpdateAlert: PriceUpdateListenerCallBack, funIOBZoneTouchAlert: IOBZoneTouchAlertListenerCallBack
    , funIOBMitigationAlert: IOBMitigationAlertListenerCallBack, funIOBNewOrderAlert: IOBNewOrderListenerCallBack, funAutoTradeAlert: AutoTradeListenerCallBack, funBrahmastraSignal?: BrahmastraSignalListenerCallBack): void {
    if (!this.connection) return;

    // Unsubscribe any existing subscriptions before setting up new ones to prevent
    // double-subscription if the component is recreated (e.g. route navigation).
    this.unsubscribeMultiSegmentTopics();

    const subscribe = () => {
      this.indexOHLCSubscription = this.connection!.subscribe('/topic/indexOHLC', message => funIndexOHLC(JSON.parse(message.body)));
      this.commonMessageSubscription = this.connection!.subscribe('/topic/commonMessage', message => funCommonMessage(JSON.parse(message.body)));
      this.indexLTPSubscription = this.connection!.subscribe('/topic/indexLTP', message => funIndexLTP(JSON.parse(message.body)));
      this.minideltasubscription = this.connection!.subscribe('/topic/miniDelta', message => funMiniDelta(JSON.parse(message.body)));
      this.tradeDecisionSubscription = this.connection!.subscribe('/topic/tradeDecision', message => funTradeDecision(JSON.parse(message.body)));

      this.priceUpdateSubscription = this.connection!.subscribe('/topic/prices', message => funPriceUpdateAlert(JSON.parse(message.body)));
      this.iobZoneTouchAlertSubscription = this.connection!.subscribe('/topic/iob/zone-touch', message => funIOBZoneTouchAlert(JSON.parse(message.body)));
      this.iobMitigationAlertSubscription = this.connection!.subscribe('/topic/iob/mitigation', message => funIOBMitigationAlert(JSON.parse(message.body)));
      this.iobNewOrderSubscription = this.connection!.subscribe('/topic/iob/new', message => funIOBNewOrderAlert(JSON.parse(message.body)));
      this.autoTradeSubscription = this.connection!.subscribe('/topic/auto-trade', message => funAutoTradeAlert(JSON.parse(message.body)));

      this.brahmastraSignalSubscription = this.connection!.subscribe('/topic/brahmastraSignal', message => {
        const signal = JSON.parse(message.body);
        this.brahmastraSignals$.next(signal);
        if (funBrahmastraSignal) {
          funBrahmastraSignal(signal);
        }
      });

      this.optionBuyingSubscription = this.connection!.subscribe('/topic/option-buying', message => {
        this.optionBuyingUpdates$.next(JSON.parse(message.body));
      });
    };

    if (this.connection.connected) {
      subscribe();
    } else {
      this.connection.connect({}, subscribe, (error: any) => {
        console.error('WebSocket connection error in listenToMultiSegmentTopics:', error);
      });
    }
  }

  handlePriceUpdate(message: any) {
    this.priceUpdates$.next(message);
  }

  handleIOBZoneTouchAlert(message: any) {
    this.zoneTouchAlerts$.next(message);
  }

  handleIOBMitigationAlert(message: any) {
    this.mitigationAlerts$.next(message);
  }

  handleIOBNewOrder(message: any) {
    this.iobUpdates$.next(message);
  }

  handleAutoTrade(message: any) {
    this.autoTradeUpdates$.next(message);
  }

  public unsubscribeMultiSegmentTopics(): void {
    [
      this.indexOHLCSubscription,
      this.commonMessageSubscription,
      this.indexLTPSubscription,
      this.minideltasubscription,
      this.tradeDecisionSubscription,
      this.priceUpdateSubscription,
      this.iobZoneTouchAlertSubscription,
      this.iobMitigationAlertSubscription,
      this.iobNewOrderSubscription,
      this.autoTradeSubscription,
      this.brahmastraSignalSubscription,
    ].forEach(sub => sub?.unsubscribe());
  }


  ngOnDestroy(): void {
    const subs = [
      this.indexOHLCSubscription,
      this.commonMessageSubscription,
      this.indexLTPSubscription,
      this.minideltasubscription,
      this.tradeDecisionSubscription,
      this.priceUpdateSubscription,
      this.iobZoneTouchAlertSubscription,
      this.iobMitigationAlertSubscription,
      this.iobNewOrderSubscription,
      this.autoTradeSubscription,
      this.brahmastraSignalSubscription,
      this.optionBuyingSubscription,
    ];
    subs.forEach(sub => sub?.unsubscribe());

    this.priceUpdates$.complete();
    this.iobUpdates$.complete();
    this.zoneTouchAlerts$.complete();
    this.mitigationAlerts$.complete();
    this.autoTradeUpdates$.complete();
    this.brahmastraSignals$.complete();
    this.optionBuyingUpdates$.complete();

    if (this.connection?.connected) {
      this.connection.disconnect();
    }
  }

  ngOnInit(): void {



  }


  // ==================== Observable Streams ====================

  getPriceUpdates(): Observable<PriceTick> {
    return this.priceUpdates$.asObservable();
  }

  getIOBUpdates(): Observable<any> {
    return this.iobUpdates$.asObservable();
  }

  getZoneTouchAlerts(): Observable<any> {
    return this.zoneTouchAlerts$.asObservable();
  }

  getMitigationAlerts(): Observable<any> {
    return this.mitigationAlerts$.asObservable();
  }

  getAutoTradeUpdates(): Observable<any> {
    return this.autoTradeUpdates$.asObservable();
  }

  getBrahmastraSignals(): Observable<any> {
    return this.brahmastraSignals$.asObservable();
  }

  getOptionBuyingUpdates(): Observable<any> {
    return this.optionBuyingUpdates$.asObservable();
  }

}
