package com.smartexpense.expensevalidator.repository;

import com.smartexpense.expensevalidator.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    // custom queries later if needed
}
