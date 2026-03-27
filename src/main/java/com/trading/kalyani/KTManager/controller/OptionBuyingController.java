package com.trading.kalyani.KTManager.controller;

import com.trading.kalyani.KTManager.entity.OptionBuyingConfig;
import com.trading.kalyani.KTManager.entity.SimulatedTrade;
import com.trading.kalyani.KTManager.service.OptionBuyingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for the Option Buying Strategy module.
 */
@RestController
@RequestMapping("/api/option-buying")
public class OptionBuyingController {

    @Autowired
    private OptionBuyingService optionBuyingService;

    /** Get current configuration. */
    @GetMapping("/config")
    public ResponseEntity<OptionBuyingConfig> getConfig() {
        return ResponseEntity.ok(optionBuyingService.getConfig());
    }

    /** Persist updated configuration. */
    @PostMapping("/config")
    public ResponseEntity<OptionBuyingConfig> updateConfig(@RequestBody OptionBuyingConfig config) {
        return ResponseEntity.ok(optionBuyingService.updateConfig(config));
    }

    /** Enable the strategy. */
    @PostMapping("/enable")
    public ResponseEntity<Map<String, Object>> enable() {
        optionBuyingService.enable();
        return ResponseEntity.ok(optionBuyingService.getStatus());
    }

    /** Disable the strategy. */
    @PostMapping("/disable")
    public ResponseEntity<Map<String, Object>> disable() {
        optionBuyingService.disable();
        return ResponseEntity.ok(optionBuyingService.getStatus());
    }

    /** Current status: enabled flag, open trade count, today's P&L. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(optionBuyingService.getStatus());
    }

    /** All currently open OPT_BUY trades. */
    @GetMapping("/open-trades")
    public ResponseEntity<List<SimulatedTrade>> getOpenTrades() {
        return ResponseEntity.ok(optionBuyingService.getOpenTrades());
    }

    /** All OPT_BUY trades placed today. */
    @GetMapping("/today-trades")
    public ResponseEntity<List<SimulatedTrade>> getTodayTrades() {
        return ResponseEntity.ok(optionBuyingService.getTodayTrades());
    }
}
