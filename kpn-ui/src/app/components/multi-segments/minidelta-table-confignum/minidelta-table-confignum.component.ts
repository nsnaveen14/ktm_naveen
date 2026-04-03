import { Component, Input, OnInit, OnDestroy, OnChanges, SimpleChanges, ChangeDetectionStrategy, ChangeDetectorRef } from '@angular/core';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { MatTableModule } from '@angular/material/table';
import { MiniDelta } from '../../../models/minidelta';
import { CommonModule } from '@angular/common';
import { AppJobConfig } from '../../../models/AppJobConfig';
import { DataService } from '../../../services/data.service';

@Component({
  selector: 'app-minidelta-table-confignum',
  imports: [MatTableModule, CommonModule],
  templateUrl: './minidelta-table-confignum.component.html',
  styleUrl: './minidelta-table-confignum.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class MinideltaTableConfignumComponent implements OnInit, OnDestroy, OnChanges {

  @Input() selectedConfigFromMultiSegment: AppJobConfig | any;

  private destroy$ = new Subject<void>();
  private miniDeltaData: { [key: number]: MiniDelta[] } = {};
  miniDeltaDataSource: MiniDelta[] = [];
  miniDeltaDataTableColumns: string[] = ['Time', 'Strike Price', 'CallOI', 'PutOI', 'StrikePCR', 'Rate OI', 'CallOIChange', 'PutOIChange'];

  constructor(private dataService: DataService, private cdr: ChangeDetectorRef) {}

  ngOnInit(): void {
    if (this.selectedConfigFromMultiSegment) {
      this.getMiniDeltaData(this.selectedConfigFromMultiSegment.appJobConfigNum);
    }
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['selectedConfigFromMultiSegment'] && !changes['selectedConfigFromMultiSegment'].firstChange) {
      const prev = changes['selectedConfigFromMultiSegment'].previousValue?.appJobConfigNum;
      if (prev !== undefined) {
        delete this.miniDeltaData[prev]; // free memory for old tab
      }
      this.getMiniDeltaData(this.selectedConfigFromMultiSegment.appJobConfigNum);
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  getMiniDeltaData(appJobConfigNum: number): void {
    this.dataService.getMiniDeltaDataByAppJobConfigNum(appJobConfigNum).pipe(takeUntil(this.destroy$)).subscribe(
      (response: MiniDelta[]) => {
        this.miniDeltaData[appJobConfigNum] = response;
        this.miniDeltaDataSource = response;
        this.cdr.markForCheck();
      }
    );
  }

  round(value: number): number {
    return Math.round(value);
  }

  addNewMiniDeltaMessage(message: MiniDelta[]): void {
    if (!message.length) return;
    const configNum = message[0].appJobConfigNum;
    if (configNum === undefined) return;

    this.miniDeltaData[configNum] = message;

    if (this.selectedConfigFromMultiSegment?.appJobConfigNum === configNum) {
      this.miniDeltaDataSource = message;
      this.cdr.markForCheck();
    }
  }
}
