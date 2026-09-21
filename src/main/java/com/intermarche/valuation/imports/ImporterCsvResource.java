package com.intermarche.valuation.imports;

import io.quarkus.hibernate.orm.panache.Panache;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionManager;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Abstract base class for REST Endpoints handling bulk imports from CSV file streams.
 * <p>
 * This class provides the generic framework for processing CSV data:
 * <ul>
 *   <li>Stream reading and buffering.</li>
 *   <li>Chunking data to balance memory usage and performance.</li>
 *   <li>Generic transaction management wrapper with rollback handling.</li>
 *   <li>Standardized JSON response building.</li>
 *   <li>Utility methods for safe parsing of CSV columns.</li>
 *   <li><b>Staged Fallback Algorithm:</b> Automatically retries failed chunks with smaller batch sizes (1000 -> 100 -> 10 -> 1).</li>
 * </ul>
 * <p>
 * Subclasses must implement the specific business logic for processing chunks
 * and handling entity creation/updates via abstract methods.
 * <p>
 * This class is designed to run on Virtual Threads via {@link RunOnVirtualThread}.
 * <p>
 * Format contract shared by every subclass: pipe-separated columns,
 * HEADER-DRIVEN — the first line names the columns, and every field is
 * resolved BY NAME from that header, never by position. The shared feed is
 * a union schema consumed by several tools (impos, imvaluation, imfid):
 * each importer declares its required column names (validated against the
 * header, file rejected naming the missing ones) and ignores every column
 * it does not know, so adding a column for another tool is invisible here
 * and reordering columns is harmless. The subclass also names its KEY
 * column (EAN, family code, store code), which drives both the bulk
 * pre-fetch and the 1-by-1 fallback lookup. The staged fallback isolates
 * poison lines: a
 * failed 1000-chunk transaction is retried in 100s, then 10s, then line by
 * line in individual transactions — one bad row costs its own error entry,
 * never the batch. The per-line checksum comparison makes re-importing the
 * same file a no-op (updatedCount counts real changes only).
 * <p>
 * Quoting is NOT supported and a leading UTF-8 BOM is stripped from the header: fields are split
 * on {@code |} with no escape mechanism, so a value must never itself contain a {@code |} — it
 * would shift every following column of the line. A malformed date, an unknown boolean token or a
 * short (truncated) line is rejected per line, never applied silently (report §3).
 */
@RunOnVirtualThread
public abstract class ImporterCsvResource {

    private static final Logger LOGGER = Logger.getLogger(ImporterCsvResource.class);

    // Staged Fallback Sizes
    protected static final int STAGE_1_SIZE = 1000;
    protected static final int STAGE_2_SIZE = 100;
    protected static final int STAGE_3_SIZE = 10;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /**
     * The JTA Transaction Manager for handling programmatic transactions.
     */
    @Inject
    TransactionManager tm;

    /**
     * Main entry point for importing a CSV stream.
     * <p>
     * Reads the first non-empty line as the HEADER, resolves every declared
     * column by name, validates that the key column and every required
     * column are present (the file is rejected naming the missing ones),
     * then processes the data lines in chunks of {@link #STAGE_1_SIZE}
     * (1000), delegating the actual processing of each chunk to the
     * abstract method {@link #processChunkWithFallback}. Columns absent
     * from the required list are resolved when present and read as null
     * when not; columns unknown to this importer are ignored — that
     * tolerance is what lets one shared feed serve several tools.
     *
     * @param inputStream The input stream containing CSV data.
     * @param keyColumn   The header name of the natural-key column (EAN, code…).
     * @param requiredColumns The header names this importer cannot work without.
     * @return A Response containing a JSON summary of created/updated counts and errors.
     */
    public Response importCsvStream(InputStream inputStream, String keyColumn, List<String> requiredColumns) {
        LOGGER.info("Starting Bulk Import from InputStream");
        int[] counters = new int[]{0, 0};
        List<String> errors = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            Map<String, Integer> header = null;
            int headerWidth = 0;
            List<LineData> parsedLines = new ArrayList<>(STAGE_1_SIZE);
            Set<String> targetCodes = new HashSet<>(STAGE_1_SIZE);
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                // Strip a leading UTF-8 BOM (U+FEFF), which trim() does not remove: left in place
                // it would make the first header cell "<BOM>CODE", so the key column would not
                // match and the whole file would be rejected with a misleading message (report §3).
                if (line.startsWith("\uFEFF")) {
                    line = line.substring(1);
                }
                line = line.trim();
                if (line.isEmpty()) continue;
                if (header == null) {
                    header = parseHeader(line);
                    // Compare a short line against the number of header POSITIONS, not the count of
                    // distinct names (report §3): a header like A|B|B|C has 3 distinct names but 4
                    // positions, and a mapped index could otherwise exceed a short line's cells
                    // without tripping the guard, reading a silently shifted value.
                    headerWidth = header.values().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
                    List<String> missing = missingColumns(header, keyColumn, requiredColumns);
                    if (!missing.isEmpty()) {
                        return Response.status(Response.Status.BAD_REQUEST)
                                .entity("{\"error\":\"Missing required columns: "
                                        + jsonEscape(String.join(", ", missing)) + "\"}")
                                .build();
                    }
                    continue;
                }
                String[] parts = line.split("\\|", -1);
                // A line with fewer cells than the header positions is a real anomaly (a truncated
                // row), reported — never silently dropped. The raw line is not embedded in the
                // message (report §3): an error report must not balloon with full payload lines.
                if (parts.length < headerWidth) {
                    errors.add("Line " + lineNumber + " ignored (fewer cells than the header: "
                            + parts.length + " < " + headerWidth + ")");
                    continue;
                }
                LineData lineData = new LineData(lineNumber, header, parts, keyColumn);
                String code = lineData.code;
                if (code == null || code.isEmpty()) {
                    errors.add("Line " + lineNumber + " ignored (empty key '" + keyColumn + "')");
                    continue;
                }
                parsedLines.add(lineData);
                targetCodes.add(code);
                if (parsedLines.size() >= STAGE_1_SIZE) {
                    Map<String, Object> contextMap = processChunkWithFallback(parsedLines, targetCodes, counters, errors);
                    processWithStages(parsedLines, contextMap, STAGE_1_SIZE, counters, errors);
                    parsedLines.clear();
                    targetCodes.clear();
                }
            }
            if (!parsedLines.isEmpty()) {
                Map<String, Object> contextMap = processChunkWithFallback(parsedLines, targetCodes, counters, errors);
                processWithStages(parsedLines, contextMap, STAGE_1_SIZE, counters, errors);
            }
        } catch (IOException e) {
            LOGGER.error("Error reading input stream", e);
            return Response.serverError().entity("Error reading file: " + e.getMessage()).build();
        } catch (Throwable e) {
            LOGGER.error("Unexpected error", e);
            return Response.serverError().entity("Unexcepted error: " + e.getMessage()).build();
        }
        LOGGER.info("Import finished. Created: " + counters[0] + ", Updated: " + counters[1]);
        StringBuilder sb = buildAnswer(counters, errors);
        return Response.ok(sb.toString()).build();
    }

    /**
     * Generic Staged Processing Algorithm (1000 -> 100 -> 10 -> 1).
     * <p>
     * This method implements the recursive logic to handle transaction failures.
     * It attempts to process the list of lines with the given {@code chunkSize}.
     * If the transaction fails, it splits the list into smaller chunks and retries.
     * <p>
     * Special case for chunkSize = 1: It forces a fresh DB lookup for each line
     * to handle stale data or concurrency issues before processing.
     *
     * @param lines         The list of data for the current chunk.
     * @param preFetchedMap A map of pre-fetched entities (Optimization). Can be null.
     * @param chunkSize     The current chunk size to attempt.
     * @param counters      An array of size 2 to hold [createdCount, updatedCount].
     * @param errors        List to collect definitive error messages.
     */
    protected void processWithStages(List<LineData> lines, Map<String, Object> preFetchedMap, int chunkSize, int[] counters, List<String> errors) {
        if (lines.isEmpty()) return;
        // Base Case: Atomic processing (1 by 1)
        if (chunkSize == 1) {
            processLineByLine(lines, counters, errors);
            return;
        }
        // Recursive Step: Try processing with the current chunk size
        withTransaction(() -> {
            int[] lCounters = {0, 0};
            for (LineData data : lines) {
                // Delegate to specific logic implemented by subclass
                processLineLogic(data, preFetchedMap, lCounters);
            }
            return lCounters;
        }).onSuccess(lCounters -> {
            updateCounters(counters, lCounters);
        }).onFailure(ex -> {
            int nextSize = getNextSize(chunkSize);
            LOGGER.warn("Failed to process chunk of size " + lines.size() + " with step " + chunkSize + ". Retrying with step " + nextSize + ". Error: " + ex.getMessage());
            // Split and Recurse
            for (int i = 0; i < lines.size(); i += nextSize) {
                int end = Math.min(i + nextSize, lines.size());
                List<LineData> subList = lines.subList(i, end);
                processWithStages(subList, preFetchedMap, nextSize, counters, errors);
            }
        });
    }

    /**
     * Fallback method to process lines one by one in separate transactions.
     * <p>
     * This ensures that a single failing row does not block others.
     * It performs a fresh DB lookup for each row via {@link #findEntityForLine(LineData)}.
     *
     * @param lines    The list of data for the current chunk.
     * @param counters An array of size 2 to hold [createdCount, updatedCount].
     * @param errors   List to collect definitive error messages.
     */
    private void processLineByLine(List<LineData> lines, int[] counters, List<String> errors) {
        for (LineData data : lines) {
            withTransaction(() -> {
                Map<String, Object> singleLineMap = prepareContextForLine(data);
                int[] lCounters = {0, 0};
                processLineLogic(data, singleLineMap, lCounters);
                return lCounters;
            }).onSuccess(lCounters -> {
                updateCounters(counters, lCounters);
            }).onFailure(rowEx -> {
                errors.add("Line " + data.lineNumber + " (" + data.code + "): " + rowEx.getMessage());
            });
        }
    }

    /**
     * Prepares a context map for a single line of data by performing a fresh lookup
     * for the corresponding entity and associating it with the line's code.
     *
     * @param data The {@link LineData} object containing the line's code and relevant information.
     * @return A map containing the line's code as the key and the corresponding entity as the value.
     *         If no entity is found, the map will be empty.
     */
    protected Map<String, Object> prepareContextForLine(LineData data) {
        // Fresh lookup for atomic operation
        Object freshEntity = findEntityForLine(data);
        // Create a temporary map for this single line
        Map<String, Object> singleLineMap = new HashMap<>();
        if (freshEntity != null) {
            singleLineMap.put(data.code, freshEntity);
        }
        return singleLineMap;
    }

    /**
     * Determines the next smaller chunk size based on the current failed size.
     *
     * @param currentSize The size that just failed.
     * @return The next smaller size (1000 -> 100 -> 10 -> 1).
     */
    protected int getNextSize(int currentSize) {
        if (currentSize > STAGE_2_SIZE) return STAGE_2_SIZE;
        if (currentSize > STAGE_3_SIZE) return STAGE_3_SIZE;
        return 1;
    }

    /**
     * Builds a JSON response string summarizing the import results.
     * <p>
     * Every error string is JSON-escaped (report H7): a quote, a backslash or a control character
     * in a CSV cell or an underlying exception message would otherwise break the JSON and could
     * inject false keys into the response.
     */
    private static StringBuilder buildAnswer(int[] counters, List<String> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"createdCount\":").append(counters[0]);
        sb.append(", \"updatedCount\":").append(counters[1]);
        if (!errors.isEmpty()) {
            // Cap the reported errors (report §3): a fully poisoned chunk would otherwise return a
            // response of hundreds of megabytes. Keep the first 100 and summarise the rest.
            int cap = Math.min(errors.size(), 100);
            sb.append(", \"errors\":[");
            for (int i = 0; i < cap; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('"').append(jsonEscape(errors.get(i))).append('"');
            }
            if (errors.size() > cap) {
                sb.append(",\"... and ").append(errors.size() - cap).append(" more\"");
            }
            sb.append("]");
        }
        sb.append("}");
        return sb;
    }

    /**
     * Escapes a string for safe inclusion inside a JSON string literal (report H7).
     * <p>
     * Escapes the backslash, the double quote and the control characters (below U+0020) that a
     * hand-built JSON answer would otherwise emit verbatim, breaking the document.
     *
     * @param value The raw value, may be null.
     * @return The escaped value, or an empty string when null.
     */
    protected static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Parses the header line into an ordered column-name → index map.
     * Names are trimmed; a duplicate name keeps its FIRST index (and is
     * logged), so a malformed header cannot silently swap fields.
     *
     * @param headerLine the first non-empty line of the file
     * @return the name → index map, in header order
     */
    private static Map<String, Integer> parseHeader(String headerLine) {
        String[] cells = headerLine.split("\\|", -1);
        Map<String, Integer> header = new LinkedHashMap<>();
        for (int i = 0; i < cells.length; i++) {
            String name = cells[i] == null ? "" : cells[i].trim();
            if (name.isEmpty()) continue;
            Integer previous = header.putIfAbsent(name, i);
            if (previous != null) {
                LOGGER.warn("Duplicate header column '" + name + "' at index " + i
                        + " ignored (first occurrence at " + previous + " wins)");
            }
        }
        return header;
    }

    /**
     * Lists the declared columns absent from the header: the key column
     * first, then every required column, in declaration order.
     *
     * @param header the parsed header map
     * @param keyColumn the natural-key column name
     * @param requiredColumns the importer's required column names
     * @return the missing names (empty when the file is importable)
     */
    private static List<String> missingColumns(Map<String, Integer> header,
                                               String keyColumn, List<String> requiredColumns) {
        List<String> missing = new ArrayList<>();
        if (!header.containsKey(keyColumn)) missing.add(keyColumn);
        for (String column : requiredColumns) {
            if (!header.containsKey(column) && !missing.contains(column)) missing.add(column);
        }
        return missing;
    }

    // --------------------------------------------------
    // Abstract Methods (To be implemented by subclasses)
    // --------------------------------------------------

    /**
     * Abstract method to be implemented by subclasses.
     * <p>
     * Defines the logic for initializing a chunk:
     * 1. Bulk fetching existing entities based on targetCodes.
     * 2. Triggering the generic staged processing via {@link #processWithStages}.
     *
     * @param parsedLines The list of data for the current chunk.
     * @param targetCodes The set of unique codes (IDs) present in this chunk.
     * @param counters    An array of size 2 to hold [createdCount, updatedCount].
     * @param errors      List to collect definitive error messages.
     */
    protected abstract Map<String, Object> processChunkWithFallback(
            List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors);

    /**
     * Abstract method containing the specific business logic for creating or updating an entity.
     * <p>
     * This method is called for every line within a transaction.
     *
     * @param data       The parsed CSV line data.
     * @param entityMap  A map of existing entities (can be pre-fetched or a fresh lookup).
     * @param counters   An array of size 2 to hold [createdCount, updatedCount].
     */
    protected abstract void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters);

    /**
     * Abstract method to find a specific entity by its code.
     * <p>
     * Used by the 1-by-1 fallback to ensure fresh data is retrieved.
     *
     * @param data The parsed CSV line data containing the code.
     * @return The found entity or null.
     */
    protected abstract Object findEntityForLine(LineData data);

    // --------------------------------------------------
    // Generic Utilities & Transaction Management
    // --------------------------------------------------

    /**
     * A generic wrapper to hold the result or exception of a processing operation.
     * <p>
     * This class acts as a container for the result of a transactional operation.
     * It allows chaining actions for success and failure scenarios via
     * {@link #onSuccess(Consumer)} and {@link #onFailure(Consumer)}.
     *
     * @param <R> The type of the result returned by the processing logic.
     */
    static class Executor<R> {
        R result;
        Exception ex;

        /**
         * Executes the provided consumer if the previous operation was successful (result is not null).
         *
         * @param success The action to perform on success.
         * @return This executor instance for method chaining.
         */
        public Executor<R> onSuccess(Consumer<R> success) {
            if (result != null) success.accept(result);
            return this;
        }

        /**
         * Executes the provided consumer if the previous operation failed (exception is not null).
         *
         * @param failure The action to perform on failure (accepts the Throwable).
         * @return This executor instance for method chaining.
         */
        public Executor<R> onFailure(Consumer<Throwable> failure) {
            if (failure != null && ex != null) failure.accept(ex);
            return this;
        }

        /**
         * Sets the successful result.
         *
         * @param result The result value.
         */
        public void setResult(R result) { this.result = result; }

        /**
         * Sets the exception that occurred during processing.
         *
         * @param ex The exception.
         */
        public void setException(Exception ex) { this.ex = ex; }
    }

    /**
     * Executes a supplier within a transactional context.
     * <p>
     * This method wraps the standard JTA transaction management (begin, commit, rollback).
     * It ensures the EntityManager is cleared after execution.
     *
     * @param processing The logic to execute within the transaction.
     * @param <R>        The return type of the processing logic.
     * @return An {@link Executor} containing the result or the exception.
     */
    public <R> Executor<R> withTransaction(Supplier<R> processing) {
        Executor<R> executor = new Executor<>();
        R result;
        try {
            tm.begin();
            result = processing.get();
            tm.commit();
            executor.setResult(result);
        } catch (Exception rowEx) {
            try {
                if (tm.getStatus() != jakarta.transaction.Status.STATUS_NO_TRANSACTION) {
                    tm.rollback();
                }
            } catch (Exception rbRowEx) {
                LOGGER.error("Error during rollback", rbRowEx);
            }
            executor.setException(rowEx);
        } finally {
            Panache.getEntityManager().clear();
        }
        return executor;
    }

    /**
     * Helper method to merge local counters into the global counters.
     * <p>
     * Adds the values from the local counters array into the global counters array.
     *
     * @param counters  The global counters array [created, updated].
     * @param lCounters The local counters array to merge in.
     */
    void updateCounters(int[] counters, int[] lCounters) {
        counters[0] += lCounters[0];
        counters[1] += lCounters[1];
    }

    // --------------------------------------------------
    // Generic Parsing Helpers
    // --------------------------------------------------

    /**
     * Retrieves the trimmed value of a column resolved by name.
     *
     * @param data The parsed CSV line.
     * @param column The header name of the column.
     * @return The trimmed string, or null when the column or cell is absent.
     */
    String safeGet(LineData data, String column) {
        return data.get(column);
    }

    /**
     * Parses a Boolean column resolved by name.
     * <p>
     * Returns {@code false} for an absent column or an empty cell. A present token must be
     * {@code true} or {@code false} (case-insensitive); any other value rejects the line (report
     * §3): {@code Boolean.parseBoolean} would otherwise read {@code 1}, {@code Y} or {@code OUI} as
     * {@code false} and silently deactivate the whole catalog when a feed changes its convention.
     *
     * @param data The parsed CSV line.
     * @param column The header name of the column.
     * @return The Boolean value, or false when the column is absent or blank.
     * @throws IllegalArgumentException when the token is present but not {@code true}/{@code false}.
     */
    boolean safeParseBoolean(LineData data, String column) {
        String val = data.get(column);
        if (val == null || val.isEmpty()) return false;
        String token = val.trim();
        if (token.equalsIgnoreCase("true")) return true;
        if (token.equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException(
                "Invalid boolean in column '" + column + "': '" + val + "' (expected true or false)");
    }

    /**
     * Parses a BigDecimal column resolved by name.
     *
     * @param data The parsed CSV line.
     * @param column The header name of the column.
     * @return The BigDecimal value, or null on any missing/invalid input.
     */
    BigDecimal safeParseBigDecimal(LineData data, String column) {
        String val = data.get(column);
        if (val == null || val.isEmpty()) return null;
        try {
            return new BigDecimal(val);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses an Integer column resolved by name.
     *
     * @param data The parsed CSV line.
     * @param column The header name of the column.
     * @return The Integer value, or null on any missing/invalid input.
     */
    Integer safeParseInt(LineData data, String column) {
        String val = data.get(column);
        if (val == null || val.isEmpty()) return null;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses a LocalDateTime column resolved by name (ISO format
     * YYYY-MM-DDTHH:MM:SS).
     *
     * @param data The parsed CSV line.
     * @param column The header name of the column.
     * @return The LocalDateTime value, or null when the column is absent or blank (an open bound).
     * @throws IllegalArgumentException when the column is PRESENT but unparseable (report H6b): a
     *         malformed date must reject the line, not silently collapse into {@code null} — which
     *         would open a validity window or change a price's natural key without any error.
     */
    LocalDateTime safeParseDateTime(LineData data, String column) {
        String val = data.get(column);
        if (val == null || val.isEmpty()) return null;
        try {
            return LocalDateTime.parse(val, DATE_FORMATTER);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Invalid date in column '" + column + "': '" + val + "' (expected YYYY-MM-DDTHH:MM:SS)");
        }
    }

    /**
     * Helper to parse comma-separated codes into a sorted list.
     * <p>
     * Splits the raw string by commas, trims each token, filters empty tokens,
     * and sorts the result to ensure consistency for checksums or queries.
     *
     * @param raw The raw string from CSV (e.g., "A, B, C").
     * @return A sorted list of non-empty codes.
     */
    List<String> parseCodes(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Parses a Double column resolved by name (GPS coordinates).
     *
     * @param data The parsed CSV line.
     * @param column The header name of the column.
     * @return The Double value, or null on any missing/invalid input.
     */
     Double safeParseDouble(LineData data, String column) {
        String val = data.get(column);
        if (val == null || val.isEmpty()) return null;
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Internal Data Transfer Object (DTO) to hold one parsed data line and
     * the header it was read under: every field access goes through
     * {@link #get(String)}, BY NAME — the row has no notion of positions.
     */
    public static class LineData {
        final int lineNumber;
        final String code;
        final String[] parts;
        final Map<String, Integer> header;

        /**
         * Creates a row bound to its header.
         *
         * @param lineNumber the 1-based line number in the file
         * @param header the column-name → index map of the file
         * @param parts the raw cells of the line
         * @param keyColumn the name of the natural-key column
         */
        public LineData(int lineNumber, Map<String, Integer> header, String[] parts, String keyColumn) {
            this.lineNumber = lineNumber;
            this.header = header;
            this.parts = parts;
            this.code = get(keyColumn);
        }

        /**
         * Returns the trimmed value of a column resolved by name, or null
         * when the column is absent from the header or the cell is beyond
         * the line's cells.
         *
         * @param column the header name of the column
         * @return the trimmed cell value, or null
         */
        public String get(String column) {
            Integer index = header.get(column);
            if (index == null || index >= parts.length) return null;
            String raw = parts[index];
            return raw == null ? null : raw.trim();
        }
    }
}
