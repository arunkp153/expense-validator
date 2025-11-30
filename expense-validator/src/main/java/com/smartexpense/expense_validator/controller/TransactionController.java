package com.smartexpense.expensevalidator.controller;

import com.smartexpense.expensevalidator.model.Transaction;
import com.smartexpense.expensevalidator.repository.TransactionRepository;
import com.smartexpense.expensevalidator.service.TransactionService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/transactions")
// @CrossOrigin(origins = "http://localhost:3000") // optional; WebConfig already handles CORS
public class TransactionController {

    private final TransactionService service;
    private final TransactionRepository repository;

    public TransactionController(TransactionService service, TransactionRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    /**
     * Upload file (form-data key 'file'). Supports .csv, .xlsx, .xls, .pdf
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadFile(@RequestParam("file") MultipartFile file) {
        try {
            List<Transaction> parsed = service.parseAndSave(file);
            Map<String, java.math.BigDecimal> summary = service.summarizeByCategory(parsed);
            return ResponseEntity.ok(Map.of("transactions", parsed, "summary", summary));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Export provided list of transactions to CSV (POST JSON body: list of transactions).
     * Alternately you can fetch transactions from DB and export.
     */
    @PostMapping(value = "/export", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> exportCsv(@RequestBody List<Transaction> txns) {
        try {
            byte[] bytes = service.exportToCsvBytes(txns);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDisposition(ContentDisposition.attachment().filename("corrected_transactions.csv").build());
            return ResponseEntity.ok().headers(headers).body(bytes);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Simple endpoint to list stored transactions
     */
    @GetMapping
    public List<Transaction> all() {
        return repository.findAll();
    }
}
