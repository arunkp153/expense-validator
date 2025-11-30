package com.smartexpense.expensevalidator.service;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import com.smartexpense.expensevalidator.model.CategoryRule;
import com.smartexpense.expensevalidator.model.Transaction;
import com.smartexpense.expensevalidator.repository.TransactionRepository;
import org.apache.commons.io.FilenameUtils;
import org.apache.poi.ss.usermodel.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.ImageType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Improved TransactionService:
 *  - Loads merchant->category mapping from categories.csv (classpath or /mnt/data/categories.csv)
 *  - Robust PDF parsing: only create transactions when we find explicit Debit/Credit/INR/(Dr)/(Cr) patterns
 *  - Avoids treating short person names as merchants
 *  - Amount sanity checks to avoid picking up balances and account numbers
 *  - New: extractSummaryFromPdf() to read overall totals printed in statement
 *  - New: computeTotals(txns, from, to) to get total spent/credit/net for date ranges
 */
@Service
public class TransactionService {

    private final TransactionRepository repository;

    // fallback built-in rules (kept small). Merchant CSV will override/add to these.
    private final List<CategoryRule> builtInRules = List.of(
            new CategoryRule("zomato", "Food"),
            new CategoryRule("swiggy", "Food"),
            new CategoryRule("uber", "Travel"),
            new CategoryRule("ola", "Travel"),
            new CategoryRule("amazon", "Shopping"),
            new CategoryRule("flipkart", "Shopping"),
            new CategoryRule("petrol", "Fuel"),
            new CategoryRule("fuel", "Fuel"),
            new CategoryRule("electricity", "Bills"),
            new CategoryRule("netflix", "Entertainment"),
            new CategoryRule("spotify", "Entertainment"),
            new CategoryRule("restaurant", "Food"),
            new CategoryRule("hotel", "Travel")
    );

    // merchant map loaded from CSV: normalized keyword -> category
    private final Map<String, String> merchantMap = new LinkedHashMap<>();

    private final DateTimeFormatter[] acceptedDateFormats = new DateTimeFormatter[] {
            DateTimeFormatter.ISO_DATE,
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd MMM yyyy"),
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
    };

    // adjustable threshold — tokens that are plain integers larger than this are considered suspicious (likely balance/account)
    private final long MAX_LIKELY_AMOUNT = 1_000_000L;

    public TransactionService(TransactionRepository repository) {
        this.repository = repository;
        loadMerchantCsvIfPresent();
        // merge built-in rules only for fuzzy fallback
        for (CategoryRule r : builtInRules) {
            merchantMap.putIfAbsent(normalizeKey(r.getKeyword()), r.getCategory());
        }
    }

    /**
     * Parse uploaded file (CSV, XLSX, PDF). Returns saved transactions.
     */
    public List<Transaction> parseAndSave(MultipartFile file) throws IOException {
        List<Transaction> parsed = parseFile(file);
        if (!parsed.isEmpty()) {
            repository.saveAll(parsed);
        }
        return parsed;
    }

    public List<Transaction> parseFile(MultipartFile file) throws IOException {
        String ext = FilenameUtils.getExtension(file.getOriginalFilename()).toLowerCase(Locale.ROOT);
        switch (ext) {
            case "csv":
                return parseCsv(file);
            case "xlsx":
            case "xls":
                return parseExcel(file);
            case "pdf":
                return parsePdf(file);
            default:
                throw new IllegalArgumentException("Unsupported file type: " + ext);
        }
    }

    private List<Transaction> parseCsv(MultipartFile file) throws IOException {
        List<Transaction> list = new ArrayList<>();
        try (InputStream is = file.getInputStream();
             InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8);
             CSVReader reader = new CSVReader(isr)) {

            String[] header = reader.readNext();
            Map<String, Integer> idx = new HashMap<>();
            if (header != null) {
                for (int i = 0; i < header.length; i++) {
                    String h = header[i] == null ? "" : header[i].toLowerCase(Locale.ROOT);
                    if (h.contains("date")) idx.put("date", i);
                    if (h.contains("desc") || h.contains("narration") || h.contains("description")) idx.put("description", i);
                    if (h.contains("amount") || h.contains("amt")) idx.put("amount", i);
                    if (h.contains("type")) idx.put("type", i);
                    if (h.contains("category")) idx.put("category", i);
                }
            }
            String[] row;
            while ((row = reader.readNext()) != null) {
                if (row.length == 0) continue;
                Transaction t = new Transaction();
                t.setSourceFile(file.getOriginalFilename());
                if (idx.containsKey("date") && idx.get("date") < row.length) {
                    t.setDate(parseDateLenient(row[idx.get("date")] ));
                }
                if (idx.containsKey("description") && idx.get("description") < row.length) {
                    t.setDescription(row[idx.get("description")]);
                } else if (row.length > 1) {
                    t.setDescription(row[Math.min(1, row.length - 1)]);
                }
                // amount: normalize comma separators and currency symbols
                if (idx.containsKey("amount") && idx.get("amount") < row.length) {
                    String raw = row[idx.get("amount")] == null ? "" : row[idx.get("amount")].replaceAll("[^0-9\\.,\\-]", "");
                    BigDecimal amt = parseAmountSafe(raw);
                    if (amt != null) t.setAmount(amt);
                } else {
                    // fallback: try to find a numeric-looking cell that is sane
                    for (String cell : row) {
                        if (cell == null) continue;
                        BigDecimal amt = parseAmountSafe(cell);
                        if (amt != null) {
                            t.setAmount(amt);
                            break;
                        }
                    }
                }
                if (idx.containsKey("type") && idx.get("type") < row.length) {
                    t.setType(row[idx.get("type")]);
                }
                if (idx.containsKey("category") && idx.get("category") < row.length) {
                    t.setOriginalCategory(row[idx.get("category")]);
                }
                applyRulesSingle(t);
                list.add(t);
            }
        } catch (CsvValidationException e) {
            throw new IOException("CSV parse error", e);
        }
        return list;
    }

    private List<Transaction> parseExcel(MultipartFile file) throws IOException {
        List<Transaction> list = new ArrayList<>();
        try (InputStream is = file.getInputStream(); Workbook wb = WorkbookFactory.create(is)) {
            Sheet sheet = wb.getSheetAt(0);
            Iterator<Row> rows = sheet.iterator();
            Map<Integer, String> headers = new HashMap<>();
            if (rows.hasNext()) {
                Row headerRow = rows.next();
                for (Cell c : headerRow) {
                    String v = c.getCellType() == CellType.STRING ? c.getStringCellValue().toLowerCase(Locale.ROOT) : "";
                    headers.put(c.getColumnIndex(), v);
                }
            }
            while (rows.hasNext()) {
                Row r = rows.next();
                if (r == null) continue;
                Transaction t = new Transaction();
                t.setSourceFile(file.getOriginalFilename());
                for (Cell c : r) {
                    String head = headers.getOrDefault(c.getColumnIndex(), "");
                    switch (c.getCellType()) {
                        case STRING:
                            String s = c.getStringCellValue();
                            if (head.contains("date")) t.setDate(parseDateLenient(s));
                            else if (head.contains("desc") || head.contains("description") || head.contains("narration")) t.setDescription(s);
                            else if (head.contains("category")) t.setOriginalCategory(s);
                            else if (head.contains("type")) t.setType(s);
                            else {
                                if (t.getDescription() == null) t.setDescription(s);
                            }
                            break;
                        case NUMERIC:
                            if (DateUtil.isCellDateFormatted(c)) {
                                LocalDate d = c.getLocalDateTimeCellValue().toLocalDate();
                                t.setDate(d);
                            } else {
                                double val = c.getNumericCellValue();
                                if (t.getAmount() == null || t.getAmount().compareTo(BigDecimal.ZERO) == 0) {
                                    t.setAmount(BigDecimal.valueOf(val));
                                }
                            }
                            break;
                        case FORMULA:
                            try {
                                if (c.getCachedFormulaResultType() == CellType.NUMERIC) {
                                    t.setAmount(BigDecimal.valueOf(c.getNumericCellValue()));
                                } else {
                                    String ss = c.getStringCellValue();
                                    if (t.getDescription() == null) t.setDescription(ss);
                                }
                            } catch (Exception ignored) { }
                            break;
                        default:
                            break;
                    }
                }
                applyRulesSingle(t);
                list.add(t);
            }
        } catch (Exception e) {
            throw new IOException("Excel parse error", e);
        }
        return list;
    }

    /**
     * PDF parsing strategy:
     *  - Extract raw text (fast). If empty, try OCR.
     *  - Only create transactions for blocks that contain explicit "Debit INR", "Credit INR", "Debit", "Debited", "Paid to", "(Dr)", "(Cr)" patterns
     *  - Extract amount via anchored regex; avoid picking up balances/headers.
     */
    private List<Transaction> parsePdf(MultipartFile file) throws IOException {
        List<Transaction> list = new ArrayList<>();
        try (InputStream is = file.getInputStream();
             PDDocument doc = PDDocument.load(is)) {

            String rawText = "";
            try {
                PDFTextStripper stripper = new PDFTextStripper();
                rawText = Optional.ofNullable(stripper.getText(doc)).orElse("").replace("\u00A0", " ").trim();
            } catch (Exception ex) {
                rawText = "";
            }

            if (rawText.isBlank()) {
                try {
                    PDFRenderer renderer = new PDFRenderer(doc);
                    Tesseract tesseract = new Tesseract();
                    // If you run locally, update datapath to your tessdata if needed:
                    // tesseract.setDatapath("C:/Program Files/Tesseract-OCR/tessdata");
                    tesseract.setLanguage("eng");
                    StringBuilder sb = new StringBuilder();
                    int pages = doc.getNumberOfPages();
                    for (int p = 0; p < pages; p++) {
                        java.awt.image.BufferedImage img = renderer.renderImageWithDPI(p, 200, ImageType.RGB);
                        try {
                            String ocr = tesseract.doOCR(img);
                            if (ocr != null) sb.append(ocr).append("\n");
                        } catch (TesseractException te) {
                            // ignore page OCR error and continue
                        }
                    }
                    rawText = sb.toString().replace("\u00A0", " ").trim();
                } catch (Exception e) {
                    rawText = "";
                }
            }

            if (rawText == null || rawText.isBlank()) {
                return list;
            }

            // Break into lines but we will inspect context blocks to find amount-bearing lines
            String[] lines = rawText.split("\\r?\\n");
            // We'll build small blocks of consecutive non-empty lines to inspect together
            List<String> blocks = collapseToBlocks(lines);

            // Patterns that indicate amounts explicitly in PhonePe-like statements:
            // "Debit INR 30.00", "Credit INR 1.50", "Debit INR 42.00"
            java.util.regex.Pattern debitCreditPattern = java.util.regex.Pattern.compile("\\b(debit|credit)\\s*(inr)?\\s*[:]?\\s*([0-9]{1,3}(?:[.,][0-9]{2,})|[0-9]+(?:[.,][0-9]+)?)", java.util.regex.Pattern.CASE_INSENSITIVE);
            // Also pattern for amounts ending with (Dr)/(Cr)
            java.util.regex.Pattern parentheticalPattern = java.util.regex.Pattern.compile("([0-9]{1,3}(?:[,\\s][0-9]{3})*(?:[.,][0-9]+)?)(?:\\s*\\(Dr\\)|\\s*\\(Cr\\))", java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Pattern inrTokenPattern = java.util.regex.Pattern.compile("\\bINR\\s*([0-9.,]+)", java.util.regex.Pattern.CASE_INSENSITIVE);

            for (String block : blocks) {
                String normalized = block.trim().replaceAll("\\s+", " ");

                // Skip header/footer-like blocks
                if (normalized.toLowerCase(Locale.ROOT).contains("page") || normalized.toLowerCase(Locale.ROOT).contains("transaction statement for"))
                    continue;

                // Try explicit debit/credit pattern first
                java.util.regex.Matcher m = debitCreditPattern.matcher(normalized);
                BigDecimal amount = null;
                String type = null;
                if (m.find()) {
                    type = m.group(1).toUpperCase(Locale.ROOT);
                    String raw = m.group(3);
                    amount = parseAmountSafe(raw);
                } else {
                    // look for "INR <amount>" occurrences but require presence of 'Debited'/'Paid to'/'Paid -'/'Paid to'/'Debit' nearby
                    if ((normalized.toLowerCase(Locale.ROOT).contains("debit") ||
                            normalized.toLowerCase(Locale.ROOT).contains("debited") ||
                            normalized.toLowerCase(Locale.ROOT).contains("paid to") ||
                            normalized.toLowerCase(Locale.ROOT).contains("paid -") ||
                            normalized.toLowerCase(Locale.ROOT).contains("payment")) ) {
                        java.util.regex.Matcher m2 = inrTokenPattern.matcher(normalized);
                        if (m2.find()) {
                            amount = parseAmountSafe(m2.group(1));
                            if (normalized.toLowerCase(Locale.ROOT).contains("credit")) type = "CREDIT";
                            else type = "DEBIT";
                        } else {
                            // fall back to parenthetical pattern like '39.00(Dr)'
                            java.util.regex.Matcher m3 = parentheticalPattern.matcher(normalized);
                            if (m3.find()) {
                                amount = parseAmountSafe(m3.group(1));
                                if (normalized.toLowerCase(Locale.ROOT).contains("(dr)")) type = "DEBIT";
                                else if (normalized.toLowerCase(Locale.ROOT).contains("(cr)")) type = "CREDIT";
                            }
                        }
                    }
                }

                // if we found a sane amount, create transaction; otherwise skip block
                if (amount != null) {
                    Transaction t = new Transaction();
                    t.setSourceFile(file.getOriginalFilename());
                    // FIX: truncate description to avoid DB errors (varchar(2000))
                    t.setDescription(truncate(normalized, 2000));
                    t.setAmount(amount);
                    t.setType(type);
                    // try to find date in the block
                    LocalDate d = findDateInText(normalized);
                    if (d != null) t.setDate(d);
                    applyRulesSingle(t);
                    list.add(t);
                }
            }

        } catch (Exception e) {
            throw new IOException("PDF parse/OCR error: " + e.getMessage(), e);
        }
        return list;
    }

    // collapse adjacent non-empty lines into blocks separated by blank lines or 'Page'
    private List<String> collapseToBlocks(String[] lines) {
        List<String> blocks = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (String ln : lines) {
            if (ln == null) continue;
            String trimmed = ln.trim();
            if (trimmed.isEmpty() || trimmed.toLowerCase(Locale.ROOT).startsWith("page")) {
                if (sb.length() > 0) {
                    blocks.add(sb.toString());
                    sb.setLength(0);
                }
            } else {
                if (sb.length() > 0) sb.append(" ");
                sb.append(trimmed);
            }
        }
        if (sb.length() > 0) blocks.add(sb.toString());
        return blocks;
    }

    // Load categories.csv (if present) into merchantMap. CSV expected: keyword,category (without headers OR with header)
    private void loadMerchantCsvIfPresent() {
        // Try classpath first
        List<Path> candidates = new ArrayList<>();
        try {
            // runtime working dir
            Path p1 = Path.of("categories.csv");
            candidates.add(p1);
        } catch (Exception ignored) {}
        try {
            Path p2 = Path.of("/mnt/data/categories.csv");
            candidates.add(p2);
        } catch (Exception ignored) {}

        // also try resource on classpath
        InputStream inRes = getClass().getResourceAsStream("/categories.csv");
        if (inRes != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(inRes, StandardCharsets.UTF_8))) {
                readMerchantCsvFromReader(br);
                return;
            } catch (Exception ignored) {}
        }

        for (Path candidate : candidates) {
            try {
                if (Files.exists(candidate)) {
                    try (BufferedReader br = Files.newBufferedReader(candidate, StandardCharsets.UTF_8)) {
                        readMerchantCsvFromReader(br);
                        return;
                    }
                }
            } catch (Exception ignored) {}
        }
        // no CSV found: nothing to load (we keep built-in ones in constructor)
    }

    private void readMerchantCsvFromReader(BufferedReader br) throws IOException {
        try (CSVReader reader = new CSVReader(br)) {
            String[] row;
            while ((row = reader.readNext()) != null) {
                if (row.length < 2) continue;
                String k = row[0] == null ? "" : row[0].trim();
                String v = row[1] == null ? "" : row[1].trim();
                if (k.isBlank() || v.isBlank()) continue;
                merchantMap.put(normalizeKey(k), v);
            }
        } catch (CsvValidationException e) {
            // ignore, but try manual line by line fallback
            br.reset();
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",", 2);
                if (parts.length < 2) continue;
                merchantMap.put(normalizeKey(parts[0]), parts[1].trim());
            }
        }
    }

    // Normalize merchant keys: lower + collapse non-alphanum
    private String normalizeKey(String k) {
        if (k == null) return "";
        return k.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private void applyRulesSingle(Transaction t) {
        String desc = (t.getDescription() == null) ? "" : t.getDescription().toLowerCase(Locale.ROOT);

        // 1) Try exact merchant map lookup (tokenized)
        String norm = normalizeKey(desc);
        // try direct substring / token membership in merchantMap keys
        for (Map.Entry<String, String> e : merchantMap.entrySet()) {
            String key = e.getKey();
            if (key.isEmpty()) continue;
            if (norm.contains(key) || Arrays.stream(norm.split("\\s+")).anyMatch(tok -> tok.equals(key))) {
                t.setCorrectedCategory(e.getValue());
                break;
            }
        }

        // 2) If still uncategorized, fallback to fuzzy match against merchantMap keys
        if (t.getCorrectedCategory() == null) {
            for (Map.Entry<String, String> e : merchantMap.entrySet()) {
                if (fuzzyContains(norm, e.getKey())) {
                    t.setCorrectedCategory(e.getValue());
                    break;
                }
            }
        }

        // 3) If still uncategorized, apply built-in fuzzy rules (but avoid classifying people)
        if (t.getCorrectedCategory() == null) {
            // protect personal names: if description is likely a person's name, skip
            if (!isLikelyPersonName(desc)) {
                for (CategoryRule r : builtInRules) {
                    String kw = normalizeKey(r.getKeyword());
                    if (fuzzyContains(norm, kw)) {
                        t.setCorrectedCategory(r.getCategory());
                        break;
                    }
                }
            }
        }

        // default
        if (t.getCorrectedCategory() == null || t.getCorrectedCategory().isBlank()) {
            t.setCorrectedCategory("Uncategorized");
        }
        if (t.getAmount() == null) t.setAmount(BigDecimal.ZERO);
    }

    // Heuristic: likely person name = short (1-3 words), all alphabetic tokens, no business keywords or numeric tokens
    private boolean isLikelyPersonName(String desc) {
        if (desc == null) return false;
        String s = desc.trim();
        // if contains digits or typical merchant words, it's not a person
        if (s.matches(".*\\d.*")) return false;
        String lower = s.toLowerCase(Locale.ROOT);
        // merchant words that, if present, indicate not a person
        String[] biz = new String[] {"shop","store","services","station","bakery","cafe","restaurant","fuel","petrol","bank","pvt","ltd","enterprise","payments","payment","openai","inr","upi","transaction","cashback","gift","card"};
        for (String b : biz) if (lower.contains(b)) return false;
        String[] tokens = s.split("\\s+");
        if (tokens.length > 3) return false;
        for (String t : tokens) {
            // allow initials like "A." or single-letter initial
            if (!t.matches("^[A-Za-z\\.]+$")) return false;
        }
        return true;
    }

    // parse amount in a safe manner; returns null if token doesn't look like a sane standalone amount
    private BigDecimal parseAmountSafe(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replaceAll("[^0-9\\.,\\-]", "").trim();
        if (cleaned.isEmpty()) return null;
        // prefer formats with decimal, but handle integers
        cleaned = cleaned.replaceAll(",", "");
        // avoid tokens that are just account references or balances: excessively long integers
        try {
            // if there's a dot -> decimal -> parse
            if (cleaned.contains(".")) {
                BigDecimal val = new BigDecimal(cleaned);
                // sanity: reject impossibly big amounts (likely balance lines)
                if (val.abs().longValue() > MAX_LIKELY_AMOUNT && val.scale() == 0) return null;
                return val;
            } else {
                // no decimal - parse as integer but apply threshold
                long v = Long.parseLong(cleaned.replaceAll("[^0-9\\-]", ""));
                if (Math.abs(v) > MAX_LIKELY_AMOUNT) {
                    // suspiciously large -> skip
                    return null;
                } else {
                    return BigDecimal.valueOf(v);
                }
            }
        } catch (Exception e) {
            return null;
        }
    }

    // find date by searching for known date patterns inside text
    private LocalDate findDateInText(String text) {
        if (text == null) return null;
        // common formats in PhonePe PDF like 'Nov 01, 2025' or '01-11-2025' etc.
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\b(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}|\\d{4}-\\d{1,2}-\\d{1,2}|[A-Za-z]{3,}\\s+\\d{1,2},\\s*\\d{4})\\b");
        java.util.regex.Matcher m = p.matcher(text);
        if (m.find()) {
            String cand = m.group(1);
            try {
                return parseDateLenient(cand);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private LocalDate parseDateLenient(String raw) {
        if (raw == null) return null;
        raw = raw.replaceAll("\"", "").trim();
        for (DateTimeFormatter fmt : acceptedDateFormats) {
            try {
                return LocalDate.parse(raw, fmt);
            } catch (Exception ignored) {}
        }
        // manual fallbacks
        try {
            if (raw.contains("/")) {
                String[] p = raw.split("/");
                if (p.length == 3) {
                    int d = Integer.parseInt(p[0]);
                    int m = Integer.parseInt(p[1]);
                    int y = Integer.parseInt(p[2]);
                    if (y < 100) y += 2000;
                    return LocalDate.of(y, m, d);
                }
            } else if (raw.contains("-")) {
                String[] p = raw.split("-");
                if (p.length == 3) {
                    // could be yyyy-mm-dd or dd-mm-yyyy - try both
                    int a = Integer.parseInt(p[0]);
                    int b = Integer.parseInt(p[1]);
                    int c = Integer.parseInt(p[2]);
                    if (a > 31) { // yyyy-mm-dd
                        return LocalDate.of(a, b, c);
                    } else { // dd-mm-yyyy
                        return LocalDate.of(c, b, a);
                    }
                }
            } else if (raw.matches("[A-Za-z]{3,}\\s+\\d{1,2},\\s*\\d{4}")) {
                DateTimeFormatter f = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);
                return LocalDate.parse(raw, f);
            }
        } catch (Exception ignored) {}
        return null;
    }

    public Map<String, java.math.BigDecimal> summarizeByCategory(List<Transaction> txns) {
        Map<String, java.math.BigDecimal> sums = new LinkedHashMap<>();
        for (Transaction t : txns) {
            String cat = t.getCorrectedCategory() == null ? "Uncategorized" : t.getCorrectedCategory();
            java.math.BigDecimal amt = t.getAmount() == null ? BigDecimal.ZERO : t.getAmount();
            sums.put(cat, sums.getOrDefault(cat, BigDecimal.ZERO).add(amt));
        }
        return sums;
    }

    public byte[] exportToCsvBytes(List<Transaction> txns) throws IOException {
        StringWriter sw = new StringWriter();
        try (BufferedWriter bw = new BufferedWriter(sw)) {
            bw.write("Date,Description,Amount,Type,OriginalCategory,CorrectedCategory");
            bw.newLine();
            DateTimeFormatter iso = DateTimeFormatter.ISO_DATE;
            for (Transaction t : txns) {
                String date = t.getDate() == null ? "" : t.getDate().format(iso);
                String desc = safeCsv(t.getDescription());
                String amt = t.getAmount() == null ? "" : t.getAmount().toPlainString();
                String type = t.getType() == null ? "" : t.getType();
                String orig = safeCsv(t.getOriginalCategory());
                String corr = safeCsv(t.getCorrectedCategory());
                bw.write(String.join(",", date, desc, amt, type, orig, corr));
                bw.newLine();
            }
            bw.flush();
        }
        return sw.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String safeCsv(String s) {
        if (s == null) return "";
        String out = s.replace("\"", "\"\"");
        if (out.contains(",") || out.contains("\"") || out.contains("\n")) {
            return "\"" + out + "\"";
        } else {
            return out;
        }
    }

    // fuzzy contains: exact contains first, then token-level fuzzy match using small Levenshtein tolerance
    private boolean fuzzyContains(String text, String keyword) {
        if (text == null || keyword == null) return false;
        text = text.toLowerCase(Locale.ROOT);
        keyword = keyword.toLowerCase(Locale.ROOT);

        if (text.contains(keyword)) return true;

        String[] tokens = text.split("\\W+");
        for (String t : tokens) {
            if (t.isBlank()) continue;
            int maxDist = Math.min(2, Math.max(1, keyword.length() / 3));
            if (levenshteinDistance(t, keyword) <= maxDist) return true;
            if (keyword.contains(t) || t.contains(keyword)) return true;
        }
        return false;
    }

    // small Levenshtein implementation (OK for short tokens)
    private int levenshteinDistance(String a, String b) {
        int la = a.length(), lb = b.length();
        int[] prev = new int[lb + 1];
        int[] curr = new int[lb + 1];

        for (int j = 0; j <= lb; j++) prev[j] = j;
        for (int i = 1; i <= la; i++) {
            curr[0] = i;
            for (int j = 1; j <= lb; j++) {
                int cost = (a.charAt(i-1) == b.charAt(j-1)) ? 0 : 1;
                curr[j] = Math.min(Math.min(prev[j] + 1, curr[j-1] + 1), prev[j-1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[lb];
    }

    /* ------------------------------
       NEW: Summary extraction & totals helpers
       ------------------------------ */

    /**
     * Try to extract printed totals (Total Withdrawal Amount / Total Deposit Amount / Opening / Closing)
     * from a PDF MultipartFile. Returns map with keys like:
     *  - "total_withdrawal"  (BigDecimal)
     *  - "total_deposit"     (BigDecimal)
     *  - "opening_balance"   (BigDecimal)
     *  - "closing_balance"   (BigDecimal)
     *
     * If a value is not found it will not be present in the returned map.
     */
    public Map<String, BigDecimal> extractSummaryFromPdf(MultipartFile file) throws IOException {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        try (InputStream is = file.getInputStream(); PDDocument doc = PDDocument.load(is)) {
            String rawText = "";
            try {
                PDFTextStripper stripper = new PDFTextStripper();
                rawText = Optional.ofNullable(stripper.getText(doc)).orElse("").replace("\u00A0", " ").trim();
            } catch (Exception ex) {
                rawText = "";
            }
            if (rawText.isBlank()) return out;

            // common summary lines in statements (adapt to other banks if needed)
            java.util.regex.Pattern withdrawalPattern = java.util.regex.Pattern.compile("Total\\s+Withdrawal\\s+Amount\\s*[:\\-]?\\s*([0-9,]+(?:[.,][0-9]+)?)\\s*\\(Dr\\)?", java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Pattern depositPattern = java.util.regex.Pattern.compile("Total\\s+Deposit\\s+Amount\\s*[:\\-]?\\s*([0-9,]+(?:[.,][0-9]+)?)\\s*\\(Cr\\)?", java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Pattern openingPattern = java.util.regex.Pattern.compile("Opening\\s+Balance\\s*[:\\-]?\\s*([0-9,]+(?:[.,][0-9]+)?)\\s*\\(Cr\\)?", java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Pattern closingPattern = java.util.regex.Pattern.compile("Closing\\s+Balance\\s*[:\\-]?\\s*([0-9,]+(?:[.,][0-9]+)?)\\s*\\(Cr\\)?", java.util.regex.Pattern.CASE_INSENSITIVE);

            java.util.regex.Matcher mw = withdrawalPattern.matcher(rawText);
            if (mw.find()) {
                BigDecimal val = parseAmountSafe(mw.group(1));
                if (val != null) out.put("total_withdrawal", val);
            }
            java.util.regex.Matcher md = depositPattern.matcher(rawText);
            if (md.find()) {
                BigDecimal val = parseAmountSafe(md.group(1));
                if (val != null) out.put("total_deposit", val);
            }
            java.util.regex.Matcher mo = openingPattern.matcher(rawText);
            if (mo.find()) {
                BigDecimal val = parseAmountSafe(mo.group(1));
                if (val != null) out.put("opening_balance", val);
            }
            java.util.regex.Matcher mc = closingPattern.matcher(rawText);
            if (mc.find()) {
                BigDecimal val = parseAmountSafe(mc.group(1));
                if (val != null) out.put("closing_balance", val);
            }
        } catch (Exception e) {
            throw new IOException("PDF summary extraction error: " + e.getMessage(), e);
        }
        return out;
    }

    /**
     * Compute totals (totalDebited, totalCredited, net) across a transaction list, optionally limited to a date range.
     *
     * @param txns list of transactions (usually from parseFile or repository)
     * @param from inclusive start date (nullable — pass null to include from beginning)
     * @param to   inclusive end date (nullable — pass null to include to end)
     * @return map with keys: total_debit, total_credit, net (credit - debit)
     */
    public Map<String, BigDecimal> computeTotals(List<Transaction> txns, LocalDate from, LocalDate to) {
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;

        for (Transaction t : txns) {
            if (t == null) continue;
            LocalDate d = t.getDate();
            if (from != null && (d == null || d.isBefore(from))) continue;
            if (to != null && (d == null || d.isAfter(to))) continue;

            // determine sign via type or description if missing
            String type = t.getType();
            if (type == null) {
                type = inferTypeIfMissing(t);
            }
            BigDecimal amt = t.getAmount() == null ? BigDecimal.ZERO : t.getAmount();
            if (type != null) {
                if (type.equalsIgnoreCase("DEBIT") || type.equalsIgnoreCase("DR") || type.equalsIgnoreCase("D")) {
                    totalDebit = totalDebit.add(amt);
                } else if (type.equalsIgnoreCase("CREDIT") || type.equalsIgnoreCase("CR")) {
                    totalCredit = totalCredit.add(amt);
                } else {
                    // unknown type: try to infer by keywords
                    String inferred = inferTypeIfMissing(t);
                    if ("DEBIT".equalsIgnoreCase(inferred)) totalDebit = totalDebit.add(amt);
                    else if ("CREDIT".equalsIgnoreCase(inferred)) totalCredit = totalCredit.add(amt);
                }
            } else {
                String inferred = inferTypeIfMissing(t);
                if ("DEBIT".equalsIgnoreCase(inferred)) totalDebit = totalDebit.add(amt);
                else if ("CREDIT".equalsIgnoreCase(inferred)) totalCredit = totalCredit.add(amt);
            }
        }

        Map<String, BigDecimal> out = new LinkedHashMap<>();
        out.put("total_debit", totalDebit);
        out.put("total_credit", totalCredit);
        out.put("net", totalCredit.subtract(totalDebit));
        return out;
    }

    // If t.type is missing, try to infer from description keywords.
    private String inferTypeIfMissing(Transaction t) {
        if (t == null) return null;
        if (t.getType() != null) return t.getType();
        String desc = t.getDescription() == null ? "" : t.getDescription().toLowerCase(Locale.ROOT);
        if (desc.contains("debit") || desc.contains("debited") || desc.contains("paid to") || desc.contains("paid -") || desc.contains("dr")) return "DEBIT";
        if (desc.contains("credit") || desc.contains("received from") || desc.contains("credited")) return "CREDIT";
        // fallback: if correctedCategory is "Salary" or "Income" treat as credit
        String cat = t.getCorrectedCategory() == null ? "" : t.getCorrectedCategory().toLowerCase(Locale.ROOT);
        if (cat.contains("salary") || cat.contains("credit") || cat.contains("income")) return "CREDIT";
        return null;
    }

    // Helper: truncate long descriptions to avoid DB column size errors
    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

}
