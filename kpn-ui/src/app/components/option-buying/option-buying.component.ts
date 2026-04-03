import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDividerModule } from '@angular/material/divider';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { Subject, interval } from 'rxjs';
import { takeUntil, startWith } from 'rxjs/operators';

import { DataService } from '../../services/data.service';
import { WebsocketService } from '../../services/web-socket.service';
import { OptionBuyingConfig, OptionBuyingStatus, OptionBuyingTrade } from '../../models/analytics.model';

@Component({
  selector: 'app-option-buying',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatCardModule, MatSlideToggleModule, MatButtonModule, MatIconModule,
    MatChipsModule, MatExpansionModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatTableModule, MatTooltipModule, MatDividerModule,
    MatProgressSpinnerModule, MatSnackBarModule
  ],
  templateUrl: './option-buying.component.html',
  styleUrls: ['./option-buying.component.css']
})
export class OptionBuyingComponent implements OnInit, OnDestroy {

  private destroy$ = new Subject<void>();

  // ── State ────────────────────────────────────────────────────────────────
  config: OptionBuyingConfig = {
    enabled: false,
    enableIob: true,
    enableBrahmastra: true,
    enableGainzAlgo: true,
    enableZeroHero: true,
    numLots: 2,
    targetPercent: 30,
    stoplossPercent: 15,
    maxOpenTrades: 3,
    maxDailyLoss: -5000,
    maxDailyTrades: 10,
    tradeStartTime: '09:20',
    tradeEndTime: '15:00',
    minSignalStrength: 'MODERATE',
    exitOnReverseSignal: true,
    // Optimization 1
    requireConfluence: false,
    minConfluenceCount: 2,
    // Optimization 2
    maxVix: 20,
    minPremium: 10,
    maxPremium: 300,
    // Optimization 3
    trailingSlEnabled: false,
    trailingActivationPct: 50,
    trailingTrailPct: 50
  };

  status: OptionBuyingStatus = {
    enabled: false,
    openTradesCount: 0,
    todayTrades: 0,
    todayPnl: 0
  };

  openTrades: OptionBuyingTrade[] = [];
  todayTrades: OptionBuyingTrade[] = [];
  loading = false;

  openTradesColumns = ['symbol', 'type', 'entry', 'target', 'sl', 'pnl', 'action'];
  todayTradesColumns = ['symbol', 'type', 'entry', 'exit', 'pnl', 'reason'];

  strengthOptions = ['STRONG', 'MODERATE', 'WEAK'];

  constructor(
    private dataService: DataService,
    private wsService: WebsocketService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadAll();

    // Refresh every 30s
    interval(30000).pipe(
      startWith(0),
      takeUntil(this.destroy$)
    ).subscribe(() => this.loadStatus());

    // WebSocket updates
    this.wsService.getOptionBuyingUpdates().pipe(
      takeUntil(this.destroy$)
    ).subscribe(update => {
      if (update) {
        this.loadOpenTrades();
        this.loadTodayTrades();
        this.loadStatus();
      }
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // ── Data loading ─────────────────────────────────────────────────────────

  loadAll(): void {
    this.loadConfig();
    this.loadStatus();
    this.loadOpenTrades();
    this.loadTodayTrades();
  }

  loadConfig(): void {
    this.dataService.getOptionBuyingConfig().pipe(takeUntil(this.destroy$)).subscribe({
      next: cfg => { if (cfg) this.config = cfg; },
      error: () => {}
    });
  }

  loadStatus(): void {
    this.dataService.getOptionBuyingStatus().pipe(takeUntil(this.destroy$)).subscribe({
      next: s => { if (s) this.status = s; },
      error: () => {}
    });
  }

  loadOpenTrades(): void {
    this.dataService.getOptionBuyingOpenTrades().pipe(takeUntil(this.destroy$)).subscribe({
      next: trades => { this.openTrades = trades || []; },
      error: () => {}
    });
  }

  loadTodayTrades(): void {
    this.dataService.getOptionBuyingTodayTrades().pipe(takeUntil(this.destroy$)).subscribe({
      next: trades => { this.todayTrades = trades || []; },
      error: () => {}
    });
  }

  // ── Toggle ────────────────────────────────────────────────────────────────

  onMasterToggle(enabled: boolean): void {
    this.loading = true;
    const call = enabled
      ? this.dataService.enableOptionBuying()
      : this.dataService.disableOptionBuying();

    call.pipe(takeUntil(this.destroy$)).subscribe({
      next: s => {
        if (s) this.status = s;
        this.config.enabled = enabled;
        this.snackBar.open(`Option Buying ${enabled ? 'ENABLED' : 'DISABLED'}`, 'OK', { duration: 3000 });
        this.loading = false;
      },
      error: () => { this.loading = false; }
    });
  }

  // ── Config save ───────────────────────────────────────────────────────────

  saveConfig(): void {
    this.loading = true;
    this.dataService.updateOptionBuyingConfig(this.config).pipe(takeUntil(this.destroy$)).subscribe({
      next: cfg => {
        if (cfg) this.config = cfg;
        this.snackBar.open('Configuration saved', 'OK', { duration: 2000 });
        this.loading = false;
      },
      error: () => {
        this.snackBar.open('Failed to save config', 'OK', { duration: 3000 });
        this.loading = false;
      }
    });
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  getSourceLabel(source: string): string {
    if (!source) return '';
    return source.replace('OPT_BUY_', '').replace('_', ' ');
  }

  getPnlClass(pnl?: number): string {
    if (pnl == null) return '';
    return pnl >= 0 ? 'pnl-positive' : 'pnl-negative';
  }

  formatPnl(pnl?: number): string {
    if (pnl == null) return '—';
    return (pnl >= 0 ? '+' : '') + '₹' + pnl.toFixed(0);
  }

  formatPrice(p?: number): string {
    return p != null ? p.toFixed(2) : '—';
  }
}
