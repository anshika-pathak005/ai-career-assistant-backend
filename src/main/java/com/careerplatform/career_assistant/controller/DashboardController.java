package com.careerplatform.career_assistant.controller;

import com.careerplatform.career_assistant.dto.response.DashboardResponse;
import com.careerplatform.career_assistant.service.DashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * GET /api/dashboard
     * Returns everything frontend needs to render the dashboard.
     * Single call replaces 4-5 separate API calls.
     * All sections null-safe — works for brand new users.
     * Protected — requires JWT token.
     */
    @GetMapping
    public ResponseEntity<DashboardResponse> getDashboard() {
        log.info("Dashboard request received");
        return ResponseEntity.ok(dashboardService.getDashboard());
    }
}