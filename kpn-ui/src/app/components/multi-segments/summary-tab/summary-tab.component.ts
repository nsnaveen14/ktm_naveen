import { Component, Input, OnInit, OnDestroy, ViewChild, AfterViewInit, ChangeDetectionStrategy, ChangeDetectorRef } from '@angular/core';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { AppJobConfig } from '../../../models/AppJobConfig';
import { TradeDecisionModel } from '../../../models/TradeDecisionModel';
import { DataService } from '../../../services/data.service';
import { CommonModule } from '@angular/common';
import { MatTableModule, MatTableDataSource } from '@angular/material/table';
import { MatPaginator, MatPaginatorModule } from '@angular/material/paginator';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { IndexOHLC } from '../../../models/IndexOHLC';

@Component({
  selector: 'app-summary-tab',
  imports: [CommonModule, MatTableModule, MatPaginatorModule, MatSelectModule, MatFormFieldModule, ReactiveFormsModule],
  templateUrl: './summary-tab.component.html',
  styleUrl: './summary-tab.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class SummaryTabComponent implements OnInit, OnDestroy, AfterViewInit {

  @Input() selectedConfigFromMultiSegment: AppJobConfig | any;
  @Input() indexOHLCData: { [key: number]: IndexOHLC } = {};
  @ViewChild(MatPaginator) paginator!: MatPaginator;

  private destroy$ = new Subject<void>();

  tradeDecisions: TradeDecisionModel[] = [];
  filteredTradeDecisions: TradeDecisionModel[] = [];
  tradeDecisionsPaginatorDataSource = new MatTableDataSource<TradeDecisionModel>();

  displayedColumns: string[] = [
    'appJobConfigNum', 'appJobConfigName', 'tradeDecisionTS', 'tradeDecision',
    'tradeDecisionType', 'entryIndexLTP', 'targetIndexLTP', 'stopLossIndexLTP',
    'indexLTP', 'status', 'trade_decision_result', 'trade_decision_result_ts',
    'swingTarget', 'swingTaken', 'confirmationTaken'
  ];

  configNumFilter = new FormControl<number[]>([]);
  tradeDecisionTypeFilter = new FormControl<string[]>(['TRENDING']); // default: show only TRENDING signals
  availableConfigNums: number[] | any = [];
  availableTradeDecisionTypes: string[] = [];
  playAudio: boolean = true;
  private audio = new Audio('alert.mp3');

  constructor(private dataService: DataService, private cdr: ChangeDetectorRef) {}

  ngOnInit(): void {
    this.clearFilter();
    this.getLatestTradeDecisions(this.selectedConfigFromMultiSegment?.appJobConfigNum ?? 0);

    this.configNumFilter.valueChanges.pipe(takeUntil(this.destroy$)).subscribe(() => this.applyFilter());
    this.tradeDecisionTypeFilter.valueChanges.pipe(takeUntil(this.destroy$)).subscribe(() => this.applyFilter());
  }

  ngAfterViewInit(): void {
    this.tradeDecisionsPaginatorDataSource.paginator = this.paginator;
  }

  ngOnDestroy(): void {
    this.audio.pause();
    this.destroy$.next();
    this.destroy$.complete();
  }

  resetView(): void {
    this.clearFilter();
    this.getLatestTradeDecisions(0);
  }

  getLatestTradeDecisions(appJobConfigNum: number): void {
    this.dataService.getTradeDecisionsByConfigNum(appJobConfigNum).pipe(takeUntil(this.destroy$)).subscribe(
      (response) => {
        this.tradeDecisions = response;
        this.filteredTradeDecisions = [...this.tradeDecisions];
        this.tradeDecisionsPaginatorDataSource.data = this.filteredTradeDecisions;

        this.availableConfigNums = [...new Set(this.tradeDecisions.map(td => td.appJobConfig?.appJobConfigNum))]
          .filter((num): num is number => typeof num === 'number')
          .sort((a, b) => a - b);

        this.availableTradeDecisionTypes = [...new Set(this.tradeDecisions.map(td => td.tradeDecisionType))]
          .filter((type): type is string => typeof type === 'string' && type.trim() !== '')
          .sort();

        if (this.paginator) {
          this.tradeDecisionsPaginatorDataSource.paginator = this.paginator;
        }
        this.applyFilter(); // apply default TRENDING filter on initial load
        this.cdr.markForCheck();
      }
    );
  }

  applyFilter(): void {
    const selectedConfigNums = this.configNumFilter.value;
    const selectedTypes = this.tradeDecisionTypeFilter.value;

    let filtered = [...this.tradeDecisions];

    if (selectedConfigNums && selectedConfigNums.length > 0) {
      filtered = filtered.filter(td =>
        td.appJobConfig?.appJobConfigNum !== undefined && selectedConfigNums.includes(td.appJobConfig.appJobConfigNum)
      );
    }
    if (selectedTypes && selectedTypes.length > 0) {
      filtered = filtered.filter(td => td.tradeDecisionType && selectedTypes.includes(td.tradeDecisionType));
    }

    this.filteredTradeDecisions = filtered;
    this.tradeDecisionsPaginatorDataSource.data = this.filteredTradeDecisions;
    if (this.paginator) this.paginator.firstPage();
    this.cdr.markForCheck();
  }

  clearFilter(): void {
    this.configNumFilter.setValue([]);
    this.tradeDecisionTypeFilter.setValue([]);
  }

  addNewTradeDecisionMessage(message: TradeDecisionModel): void {
    this.tradeDecisions.unshift(message);

    const selectedConfigNums = this.configNumFilter.value;
    const selectedTypes = this.tradeDecisionTypeFilter.value;
    const noFilters = (!selectedConfigNums || selectedConfigNums.length === 0)
                   && (!selectedTypes || selectedTypes.length === 0);

    if (noFilters) {
      this.filteredTradeDecisions = this.tradeDecisions;
    } else {
      let include = true;
      if (selectedConfigNums && selectedConfigNums.length > 0) {
        include = include && message.appJobConfig?.appJobConfigNum !== undefined
                         && selectedConfigNums.includes(message.appJobConfig.appJobConfigNum);
      }
      if (selectedTypes && selectedTypes.length > 0) {
        include = include && !!message.tradeDecisionType && selectedTypes.includes(message.tradeDecisionType);
      }
      if (include) this.filteredTradeDecisions.unshift(message);
    }

    this.tradeDecisionsPaginatorDataSource.data = this.filteredTradeDecisions;
    if (this.paginator) this.tradeDecisionsPaginatorDataSource.paginator = this.paginator;

    if (this.playAudio) this.audio.play().catch(() => {});
    this.cdr.markForCheck();
  }

  updateAllOpenTrades(): void {
    const selectedConfigNums = this.configNumFilter.value;
    const appJobConfigNums = (selectedConfigNums && selectedConfigNums.length > 0)
      ? [...selectedConfigNums]
      : [-1];

    this.dataService.updateAllOpenTrades(appJobConfigNums).pipe(takeUntil(this.destroy$)).subscribe(
      (response) => {
        if (response > 0) {
          this.getLatestTradeDecisions(0);
        }
      }
    );
  }
}
