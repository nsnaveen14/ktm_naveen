import { Component, Input, OnInit, OnDestroy, OnChanges, SimpleChanges, ViewChild, AfterViewInit, ChangeDetectionStrategy, ChangeDetectorRef } from '@angular/core';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { AppJobConfig } from '../../../models/AppJobConfig';
import { CommonModule } from '@angular/common';
import { DataService } from '../../../services/data.service';
import { IndexLTPModel } from '../../../models/indexLTPModel';
import { MatTableModule, MatTableDataSource } from '@angular/material/table';
import { MatPaginator, MatPaginatorModule } from '@angular/material/paginator';
import { TradeFilterService } from '../../../services/trade-filter.service';

@Component({
  selector: 'app-index-ltpticker',
  imports: [CommonModule, MatTableModule, MatPaginatorModule],
  templateUrl: './index-ltpticker.component.html',
  styleUrl: './index-ltpticker.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class IndexLtptickerComponent implements OnInit, OnDestroy, OnChanges, AfterViewInit {

  @Input() selectedConfigFromMultiSegment: AppJobConfig | any;
  @ViewChild(MatPaginator) paginator!: MatPaginator;

  private destroy$ = new Subject<void>();
  private filterTradeDecisions = false;
  private indexLTPData: { [key: number]: IndexLTPModel[] } = {};

  displayedColumns: string[] = [
    'appJobConfigNum', 'indexTS', 'indexLTP',
    'meanStrikePCR', 'meanRateOI', 'combiRate',
    'support', 'resistance', 'range',
    'tradeDecision', 'maxPainSP', 'maxPainSPSecond'
  ];

  indexLTPPaginatorDataSource = new MatTableDataSource<IndexLTPModel>();

  constructor(private dataService: DataService, private tradeFilterService: TradeFilterService, private cdr: ChangeDetectorRef) {}

  ngOnInit(): void {
    this.tradeFilterService.filterTradeDecisions$.pipe(takeUntil(this.destroy$)).subscribe(value => {
      this.filterTradeDecisions = value;
    });

    if (this.selectedConfigFromMultiSegment) {
      this.getIndexLTPData(this.selectedConfigFromMultiSegment.appJobConfigNum);
    }
  }

  ngAfterViewInit(): void {
    this.indexLTPPaginatorDataSource.paginator = this.paginator;
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['selectedConfigFromMultiSegment'] && !changes['selectedConfigFromMultiSegment'].firstChange) {
      const prev = changes['selectedConfigFromMultiSegment'].previousValue?.appJobConfigNum;
      if (prev !== undefined) {
        delete this.indexLTPData[prev]; // free memory for old tab
      }
      this.getIndexLTPData(this.selectedConfigFromMultiSegment.appJobConfigNum);
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  getIndexLTPData(appJobConfigNum: number): void {
    this.dataService.getIndexLTPDataByConfigNum(appJobConfigNum).pipe(takeUntil(this.destroy$)).subscribe(
      (response: IndexLTPModel[]) => {
        this.indexLTPData[appJobConfigNum] = response;
        this.refreshDataSource();
      }
    );
  }

  addNewIndexLTPMessage(message: IndexLTPModel): void {
    const configNum = message.appJobConfigNum;
    if (configNum === undefined) return;

    if (!this.indexLTPData[configNum]) {
      this.indexLTPData[configNum] = [];
    }
    this.indexLTPData[configNum].unshift(message);

    if (this.selectedConfigFromMultiSegment?.appJobConfigNum === configNum) {
      this.refreshDataSource();
    }
  }

  applyTradeDecisionFilter(enableFilter: boolean): void {
    this.filterTradeDecisions = enableFilter;
    this.tradeFilterService.setFilterTradeDecisions(enableFilter);
    this.refreshDataSource();
  }

  private refreshDataSource(): void {
    const configNum = this.selectedConfigFromMultiSegment?.appJobConfigNum;
    const all = configNum !== undefined ? (this.indexLTPData[configNum] ?? []) : [];

    this.indexLTPPaginatorDataSource.data = this.filterTradeDecisions
      ? all.filter(item => item.tradeDecision === 'BUY' || item.tradeDecision === 'SELL')
      : all;

    if (this.paginator) {
      this.indexLTPPaginatorDataSource.paginator = this.paginator;
    }
    this.cdr.markForCheck();
  }
}
