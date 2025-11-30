package com.smartexpense.expensevalidator.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Simple health/root endpoint to avoid the Whitelabel 404 when visiting the app root.
 */
@RestController
public class HealthController {
    @GetMapping("/")
    public ResponseEntity<String> root() {
        return ResponseEntity.ok("ExpenseValidator API running");
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
