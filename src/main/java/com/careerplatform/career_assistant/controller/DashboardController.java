package com.careerplatform.career_assistant.controller;

import com.careerplatform.career_assistant.dto.response.DashboardResponse;
import com.careerplatform.career_assistant.security.CurrentUserId;
import com.careerplatform.career_assistant.service.DashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * GET /api/v1/dashboard
     * Everything the dashboard needs in one call, replacing 4-5 separate requests.
     * All sections are null-safe, so this works for a brand new user.
     */
    @GetMapping
    public ResponseEntity<DashboardResponse> getDashboard(@CurrentUserId Long userId) {
        log.info("Dashboard request for userId: {}", userId);
        return ResponseEntity.ok(dashboardService.getDashboard(userId));
    }
}
