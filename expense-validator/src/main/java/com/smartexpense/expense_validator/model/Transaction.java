package com.smartexpense.expensevalidator.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "transactions")
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate date;

    @Column(length = 2000)
    private String description;

    private BigDecimal amount;

    private String type;

    private String originalCategory;

    private String correctedCategory;

    private String sourceFile;

    // constructors, getters, setters

    public Transaction() {}

    // simple fluent setters/getters (or use Lombok if you prefer)
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getOriginalCategory() { return originalCategory; }
    public void setOriginalCategory(String originalCategory) { this.originalCategory = originalCategory; }

    public String getCorrectedCategory() { return correctedCategory; }
    public void setCorrectedCategory(String correctedCategory) { this.correctedCategory = correctedCategory; }

    public String getSourceFile() { return sourceFile; }
    public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }
}
