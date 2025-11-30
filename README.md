
# **Expense Validator**

A full-stack **Spring Boot + React** project that allows users to upload bank statements (PDF, CSV, XLSX), extract transactions, auto-categorize expenses, and export cleaned data.

## **Features**

* Upload PDF / CSV / XLSX statements
* Extract transactions (date, description, amount, debit/credit)
* Auto-categorize using rules & keywords
* Summary of expenses by category
* Export cleaned transactions to CSV
* Clean React UI with progress bar and table view

## **Tech Stack**

**Backend:** Spring Boot, JPA, PDFBox, Apache POI, OpenCSV
**Frontend:** React, JSX, Fetch/XHR

## **How to Run Backend**

```
cd expense-validator
mvn spring-boot:run
```

Backend runs at: **[http://localhost:8080](http://localhost:8080)**

## **How to Run Frontend**

```
cd expense-ui
npm install
npm start
```

Frontend runs at: **[http://localhost:3000](http://localhost:3000)**

## **Main API**

```
POST /api/transactions/upload    (multipart/form-data)
POST /api/transactions/export    (export CSV)
GET  /api/transactions
```

## **Project Structure**

```
expense-validator/   (Spring Boot backend)
expense-ui/          (React frontend)
```

## **Description**

A simple tool that reads bank statements, organizes expenses, and gives a clear categorized summary.


