import { Component, OnInit, OnDestroy, ViewChild } from '@angular/core';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { DataService } from '../../services/data.service';
import { WebsocketService } from '../../services/web-socket.service';
import { CommonReqRes } from '../../models/CommonReqRes';
import { IndexOHLC } from '../../models/IndexOHLC';
import { MessageService } from '../../services/message.service';
import { AppJobConfig } from '../../models/AppJobConfig';
import { CommonModule } from '@angular/common';
import { MatTabsModule } from '@angular/material/tabs';
import { SummaryTabComponent } from './summary-tab/summary-tab.component';
import { IndexLtptickerComponent } from './index-ltpticker/index-ltpticker.component';
import { MinideltaTableConfignumComponent } from './minidelta-table-confignum/minidelta-table-confignum.component';
import { IndexLTPModel } from '../../models/indexLTPModel';
import { MiniDelta } from '../../models/minidelta';
import { FormsModule } from '@angular/forms';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { TradeFilterService } from '../../services/trade-filter.service';
import { SwingCandleModalComponent } from '../swing-candle-modal/swing-candle-modal.component';
import { SwingPointData } from '../../models/SwingPointData';
import { NgbModal } from '@ng-bootstrap/ng-bootstrap';
import { PriceTick } from '../../models/performance.model';

@Component({
  selector: 'app-multi-segments',
  imports: [CommonModule, MatTabsModule, SummaryTabComponent, IndexLtptickerComponent, MinideltaTableConfignumComponent, FormsModule, MatSlideToggleModule],
  templateUrl: './multi-segments.component.html',
  styleUrl: './multi-segments.component.css'
})
export class MultiSegmentsComponent implements OnInit, OnDestroy {

  private destroy$ = new Subject<void>();

  appJobConfigDetails: AppJobConfig[] | any;
  selectedAppJobConfigNum: number = 0;
  selectedConfig: AppJobConfig | undefined;
  selectedTabIndex: number = 0;
  indexOHLCData: { [key: number]: IndexOHLC } = {};
  currentIndexOHLCData: IndexOHLC | any;

  showOnlyTradeDecisions: boolean = false;

  @ViewChild('indexLtptickerRef') indexLtptickerComponent!: IndexLtptickerComponent;
  @ViewChild('deltaTableRef') deltaTableComponent!: MinideltaTableConfignumComponent;
  @ViewChild('summaryTabTradeDecisionsRef') summaryTabTradeDecisionsComponent!: SummaryTabComponent;

  constructor(
    private dataService: DataService,
    private webSocketService: WebsocketService,
    private messageService: MessageService,
    private tradeFilterService: TradeFilterService,
    private modalService: NgbModal
  ) {}

  toggleTradeDecisionFilter(): void {
    this.showOnlyTradeDecisions = !this.showOnlyTradeDecisions;
    this.tradeFilterService.setFilterTradeDecisions(this.showOnlyTradeDecisions);
    if (this.indexLtptickerComponent) {
      this.indexLtptickerComponent.applyTradeDecisionFilter(this.showOnlyTradeDecisions);
    }
  }

  ngOnInit(): void {
    this.tradeFilterService.filterTradeDecisions$.pipe(takeUntil(this.destroy$)).subscribe(value => {
      this.showOnlyTradeDecisions = value;
    });

    this.getAppJobConfigDetails();

    this.webSocketService.listenToMultiSegmentTopics(
      (indexOHLCMessage) => {
        const appJobConfigNum = indexOHLCMessage.appJobConfigNum;
        this.indexOHLCData[appJobConfigNum] = indexOHLCMessage;
        if (this.selectedAppJobConfigNum === appJobConfigNum)
          this.currentIndexOHLCData = this.indexOHLCData[this.selectedAppJobConfigNum];
      },
      (commonMessage) => {
        this.messageService.sendMessage(commonMessage);
      },
      (indexLTPMessage) => {
        this.handleIndexLTPMessage(indexLTPMessage);
      },
      (miniDeltaMessage) => {
        this.handleMiniDeltaMessage(miniDeltaMessage);
      },
      (tradeDecisionMessage) => {
        this.handleTradeDecisionMessage(tradeDecisionMessage);
      },
      (priceTick: PriceTick) => {
        this.webSocketService.handlePriceUpdate(priceTick);
      },
      (iobZoneTouchAlert: any) => {
        this.webSocketService.handleIOBZoneTouchAlert(iobZoneTouchAlert);
      },
      (iobMitigationAlert: any) => {
        this.webSocketService.handleIOBMitigationAlert(iobMitigationAlert);
      },
      (iobNewOrderAlert: any) => {
        this.webSocketService.handleIOBNewOrder(iobNewOrderAlert);
      },
      (autoTradeAlert: any) => {
        this.webSocketService.handleAutoTrade(autoTradeAlert);
      }
    );
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.webSocketService.unsubscribeMultiSegmentTopics();
  }

  handleIndexLTPMessage(indexLTPMessage: IndexLTPModel): void {
    if (this.indexLtptickerComponent) {
      this.indexLtptickerComponent.addNewIndexLTPMessage(indexLTPMessage);
    }
  }

  handleMiniDeltaMessage(miniDeltaMessage: MiniDelta[]): void {
    if (this.deltaTableComponent) {
      this.deltaTableComponent.addNewMiniDeltaMessage(miniDeltaMessage);
    }
  }

  handleTradeDecisionMessage(tradeDecisionMessage: any): void {
    if (this.summaryTabTradeDecisionsComponent) {
      this.summaryTabTradeDecisionsComponent.addNewTradeDecisionMessage(tradeDecisionMessage);
    }
  }

  getAppJobConfigDetails() {
    this.dataService.getAppJobConfigDetails().pipe(takeUntil(this.destroy$)).subscribe(res => {
      this.appJobConfigDetails = res;
      this.updateSelectedConfig();
    });
  }

  onTabChange(index: number): void {
    this.selectedTabIndex = index;
    this.selectedAppJobConfigNum = this.appJobConfigDetails[index].appJobConfigNum;
    this.updateSelectedConfig();
    this.currentIndexOHLCData = this.indexOHLCData[this.selectedAppJobConfigNum];
  }

  updateSelectedConfig(): void {
    this.selectedConfig = this.appJobConfigDetails?.find(
      (config: AppJobConfig) => config.appJobConfigNum === this.selectedAppJobConfigNum
    );
  }

  startJob(): void {
    if (this.selectedConfig) {
      this.dataService.startJob(this.selectedConfig.appJobConfigNum).pipe(takeUntil(this.destroy$)).subscribe({
        error: () => this.messageService.sendMessage(new CommonReqRes(false, 'Error in starting job', 0, null, 'error'))
      });
    }
  }

  stopJob(): void {
    if (this.selectedConfig) {
      this.dataService.stopJob(this.selectedConfig.appJobConfigNum).pipe(takeUntil(this.destroy$)).subscribe({
        next: () => this.messageService.sendMessage(new CommonReqRes(false, 'Job Stopped Successfully', 0, null, 'error')),
        error: () => this.messageService.sendMessage(new CommonReqRes(false, 'Error in stopping job', 0, null, 'error'))
      });
    }
  }

  getSwingCandleData() {
    if (this.selectedConfig) {
      this.dataService.getSwingHighLowByConfigNum(this.selectedConfig.appJobConfigNum).pipe(takeUntil(this.destroy$)).subscribe({
        next: (data: SwingPointData) => {
          const modalRef = this.modalService.open(SwingCandleModalComponent, { size: 'xl' });
          modalRef.componentInstance.swingCandleData = data;
        }
      });
    }
  }

  exitMarket() {
    this.dataService.exitMarket().pipe(takeUntil(this.destroy$)).subscribe(res => {
      const commonMessage = res > 0
        ? new CommonReqRes(false, 'All orders are closed', res, null, 'success')
        : new CommonReqRes(false, 'Error occured during exit market operation.', res, null, 'error');
      this.messageService.sendMessage(commonMessage);
    });
  }

  cancelOpenOrders() {
    this.dataService.cancelOpenOrders().pipe(takeUntil(this.destroy$)).subscribe(res => {
      this.messageService.sendMessage(res);
    });
  }
}
