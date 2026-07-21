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
     * Reads the stream line by line, skips the header, and processes the data
     * in chunks of {@link #STAGE_1_SIZE} (1000). It delegates the actual processing
     * of each chunk to the abstract method {@link #processChunkWithFallback}.
     *
     * @param inputStream The input stream containing CSV data.
     * @param colNumber   The expected number of columns per line. Lines with fewer columns are ignored.
     * @return A Response containing a JSON summary of created/updated counts and errors.
     */
    public Response importCsvStream(InputStream inputStream, int colNumber) {
        LOGGER.info("Starting Bulk Import from InputStream");
        int[] counters = new int[]{0, 0};
        List<String> errors = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            List<LineData> parsedLines = new ArrayList<>(STAGE_1_SIZE);
            Set<String> targetCodes = new HashSet<>(STAGE_1_SIZE);
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();
                if (line.isEmpty()) continue;
                if (lineNumber == 1) continue; // Skip header
                String[] parts = line.split("\\|", -1);
                // Validate column count
                if (parts.length < colNumber) {
                    errors.add("Line " + lineNumber + " ignored (not enough columns): " + line);
                    continue;
                }
                String code = parts[0].trim();
                parsedLines.add(new LineData(lineNumber, code, parts));
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
     */
    private static StringBuilder buildAnswer(int[] counters, List<String> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"createdCount\":").append(counters[0]);
        sb.append(", \"updatedCount\":").append(counters[1]);
        if (!errors.isEmpty()) {
            sb.append(", \"errors\":[");
            sb.append(String.join("\",\"", errors));
            sb.append("\"]");
        }
        sb.append("}");
        return sb;
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
     * Safely retrieves a string from an array by index.
     * <p>
     * Handles array index out of bounds exceptions safely. Trims the result.
     *
     * @param parts The string array.
     * @param index The index to retrieve.
     * @return The trimmed string or null if index is out of bounds.
     */
    String safeGet(String[] parts, int index) {
        return index >= 0 && index < parts.length ? (parts[index] == null ? null : parts[index].trim()) : null;
    }

    /**
     * Safely parses a Boolean from an array by index.
     * <p>
     * Handles bounds checking, empty strings, and parsing errors.
     * Returns {@code false} as a default for any error case.
     *
     * @param parts The string array.
     * @param index The index to parse.
     * @return The Boolean value or false if parsing fails or index is out of bounds.
     */
    boolean safeParseBoolean(String[] parts, int index) {
        if (index >= parts.length) return false;
        String val = parts[index].trim();
        if (val.isEmpty()) return false;
        return Boolean.parseBoolean(val);
    }

    /**
     * Safely parses a BigDecimal from an array by index.
     * <p>
     * Handles bounds checking, empty strings, and number format exceptions.
     *
     * @param parts The string array.
     * @param index The index to parse.
     * @return The BigDecimal value or null if parsing fails or index is out of bounds.
     */
    BigDecimal safeParseBigDecimal(String[] parts, int index) {
        if (index >= parts.length) return null;
        String val = parts[index].trim();
        if (val.isEmpty()) return null;
        try {
            return new BigDecimal(val);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Safely parses an Integer from an array by index.
     * <p>
     * Handles bounds checking, empty strings, and number format exceptions.
     *
     * @param parts The string array.
     * @param index The index to parse.
     * @return The Integer value or null if parsing fails or index is out of bounds.
     */
    Integer safeParseInt(String[] parts, int index) {
        if (index >= parts.length) return null;
        String val = parts[index].trim();
        if (val.isEmpty()) return null;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Safely parses a LocalDateTime from an array by index.
     * <p>
     * Uses ISO format (YYYY-MM-DDTHH:MM:SS). Handles bounds checking,
     * empty strings, and parsing errors.
     *
     * @param parts The string array.
     * @param index The index to parse.
     * @return The LocalDateTime value or null if parsing fails or index is out of bounds.
     */
    LocalDateTime safeParseDateTime(String[] parts, int index) {
        if (index >= parts.length) return null;
        String val = parts[index].trim();
        if (val.isEmpty()) return null;
        try {
            return LocalDateTime.parse(val, DATE_FORMATTER);
        } catch (Exception e) {
            LOGGER.warn("Invalid date format at index " + index + ": " + val);
            return null;
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
     * Safely parses a Double from an array by index.
     * <p>
     * Helper method specific to this resource for GPS coordinates.
     *
     * @param parts The string array.
     * @param index The index to parse.
     * @return The Double value or null if parsing fails or index is out of bounds.
     */
     Double safeParseDouble(String[] parts, int index) {
        if (index >= parts.length) return null;
        String val = parts[index].trim();
        if (val.isEmpty()) return null;
        try {
            Double value = Double.parseDouble(val);
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Internal Data Transfer Object (DTO) to hold parsed line data.
     */
    public static class LineData {
        final int lineNumber;
        final String code;
        final String[] parts;

        public LineData(int lineNumber, String code, String[] parts) {
            this.lineNumber = lineNumber;
            this.code = code;
            this.parts = parts;
        }
    }
}