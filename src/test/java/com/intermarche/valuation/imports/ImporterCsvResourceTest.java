package com.intermarche.valuation.imports;

import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.persistence.EntityManager;
import jakarta.transaction.TransactionManager;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ImporterCsvResource}.
 * <p>
 * This class tests the generic CSV parsing (header-driven, columns resolved
 * BY NAME), transaction management, and fallback logic provided by the
 * abstract base class.
 */
@QuarkusTest
public class ImporterCsvResourceTest {

    /**
     * The mocked TransactionManager.
     */
    @Mock
    TransactionManager transactionManager;

    /**
     * The resource under test.
     */
    private TestImporter resource;

    /**
     * Mocked static Panache context.
     */
    private MockedStatic<Panache> panacheMock;

    /** Header names of the test rows, in cell order. */
    private static final String[] TEST_HEADER = {"code", "name"};

    /**
     * Sets up the test environment.
     * Initializes mocks and the resource.
     */
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        resource = new TestImporter();
        // Manual injection of the mock into the resource
        resource.tm = transactionManager;
        // Mock Panache to avoid side effects on the EntityManager
        panacheMock = Mockito.mockStatic(Panache.class);
        EntityManager em = Mockito.mock(EntityManager.class);
        panacheMock.when(Panache::getEntityManager).thenReturn(em);
    }

    /**
     * Tears down the test environment.
     * Closes the static mock.
     */
    @AfterEach
    void tearDown() {
        panacheMock.close();
    }

    /**
     * Builds a header-bound row for the test importer: the header maps the
     * TEST_HEADER names onto the cell positions and "code" is the key column.
     *
     * @param lineNumber The 1-based line number.
     * @param cells The raw cells of the row.
     * @return The header-bound line.
     */
    private static ImporterCsvResource.LineData line(int lineNumber, String... cells) {
        Map<String, Integer> header = new LinkedHashMap<>();
        for (int i = 0; i < TEST_HEADER.length; i++) {
            header.put(TEST_HEADER[i], i);
        }
        return new ImporterCsvResource.LineData(lineNumber, header, cells, TEST_HEADER[0]);
    }

    // --------------------------------------------------
    // Tests for Executor
    // --------------------------------------------------

    /**
     * Tests the case where the 'failure' consumer is null in onFailure.
     * Covers: if (failure != null && ex != null) failure.accept(ex);
     */
    @Test
    void testExecutor_OnFailure_NullConsumer() {
        ImporterCsvResource.Executor<String> executor = new ImporterCsvResource.Executor<>();
        RuntimeException ex = new RuntimeException("Test");
        executor.setException(ex);
        // Call with null (must not throw NPE)
        assertDoesNotThrow(() -> executor.onFailure(null));
        // Verification that the exception is present
        assertEquals(ex, executor.ex);
    }

    // --------------------------------------------------
    // Tests for importCsvStream
    // --------------------------------------------------

    /**
     * Tests successful CSV import with valid data.
     * Verifies response status, parsed lines, and JSON content.
     */
    @Test
    void testImportCsvStream_Success() throws Exception {
        String csvContent = "code|name\n" +
                "CODE1|Item 1\n" +
                "CODE2|Item 2";
        ByteArrayInputStream stream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        Response response = resource.importCsvStream(stream, "code", List.of("name"));
        assertEquals(200, response.getStatus());
        // Verify the abstract method was called to process lines
        assertEquals(2, resource.capturedLines.size());
        assertEquals("CODE1", resource.capturedLines.get(0).code);
        assertEquals("CODE2", resource.capturedLines.get(1).code);
        // Verify response JSON content
        String entity = response.getEntity().toString();
        assertTrue(entity.contains("\"createdCount\":2"));
    }

    /**
     * Tests the handling of generic exceptions (Throwable) during import.
     * <p>
     * Covers the `catch (Throwable e)` block in {@code importCsvStream}.
     * Simulates a RuntimeException occurring during the chunk processing phase to ensure
     * a 500 Internal Server Error is returned with the specific error message.
     */
    @Test
    void testImportCsvStream_GenericException() {
        // Enable the flag in the TestImporter to throw an exception
        resource.throwGenericException = true;

        String csvContent = "code|name\n" +
                "CODE1|Item 1";
        ByteArrayInputStream stream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        Response response = resource.importCsvStream(stream, "code", List.of("name"));

        // Verify that the catch (Throwable e) block was triggered
        assertEquals(500, response.getStatus());
        String entity = response.getEntity().toString();
        // Note: The source code has a typo "Unexcepted", we match it exactly.
        assertTrue(entity.contains("Unexcepted error"));
        assertTrue(entity.contains("Simulated Generic Exception for Testing Catch Throwable"));
    }

    /**
     * Tests CSV import handling of lines with fewer cells than the header.
     * Verifies that truncated lines are skipped and added to the error list.
     */
    @Test
    void testImportCsvStream_WrongColumnCount() throws Exception {
        String csvContent = "code|name|extra\n" +
                "CODE1|Item|1\n" +
                "CODE2|Item"; // Missing 3rd cell
        ByteArrayInputStream stream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        Response response = resource.importCsvStream(stream, "code", List.of("name", "extra"));
        assertEquals(200, response.getStatus()); // Returns 200 with errors list, not 500
        String entity = response.getEntity().toString();
        assertTrue(entity.contains("\"createdCount\":1")); // Only CODE1 processed
        assertTrue(entity.contains("errors"));
        assertTrue(entity.contains("fewer cells than the header"));
    }

    /**
     * Tests that a header missing the key column or a required column rejects
     * the file with a 400 naming the missing columns, before any processing.
     */
    @Test
    void testImportCsvStream_MissingRequiredColumns() {
        String csvContent = "other|name\n" +
                "CODE1|Item 1\n";
        ByteArrayInputStream stream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        Response response = resource.importCsvStream(stream, "code", List.of("name", "extra"));
        assertEquals(400, response.getStatus());
        assertEquals("{\"error\":\"Missing required columns: code, extra\"}", response.getEntity().toString());
        assertTrue(resource.capturedLines.isEmpty());
    }

    /**
     * Tests that a duplicate header name keeps its FIRST index (first
     * occurrence wins): the key is read from the first "code" column.
     */
    @Test
    void testImportCsvStream_DuplicateHeaderFirstWins() {
        String csvContent = "code|code\n" +
                "FIRST|SECOND\n";
        ByteArrayInputStream stream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        Response response = resource.importCsvStream(stream, "code", List.of());
        assertEquals(200, response.getStatus());
        assertEquals(1, resource.capturedLines.size());
        assertEquals("FIRST", resource.capturedLines.get(0).code);
        assertTrue(response.getEntity().toString().contains("\"createdCount\":1"));
    }

    /**
     * Tests that a data line whose key cell is empty is reported and skipped
     * (empty-key arm) without stopping the import of the other lines.
     */
    @Test
    void testImportCsvStream_EmptyKeyLine() {
        String csvContent = "code|name\n" +
                "|orphan\n" +
                "CODE1|Item 1\n";
        ByteArrayInputStream stream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        Response response = resource.importCsvStream(stream, "code", List.of("name"));
        assertEquals(200, response.getStatus());
        assertEquals(1, resource.capturedLines.size());
        assertEquals("CODE1", resource.capturedLines.get(0).code);
        String entity = response.getEntity().toString();
        assertTrue(entity.contains("\"createdCount\":1"));
        assertTrue(entity.contains("Line 2 ignored (empty key 'code')"));
    }

    /**
     * Tests CSV import handling of empty lines or lines containing only whitespace.
     */
    @Test
    void testImportCsvStream_EmptyLines() throws Exception {
        String csvContent = "code|name\n" +
                "\n" +
                "CODE1|Item 1\n" +
                "   \n";
        ByteArrayInputStream stream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        Response response = resource.importCsvStream(stream, "code", List.of("name"));
        assertEquals(200, response.getStatus());
        assertEquals(1, resource.capturedLines.size());
    }

    /**
     * Tests the basic chunking mechanism with a small dataset.
     */
    @Test
    void testImportCsvStream_Chunking() throws Exception {
        StringBuilder sb = new StringBuilder("code|name\n");
        for (int i = 0; i < 5; i++) {
            sb.append("CODE").append(i).append("|Name").append(i).append("\n");
        }
        ByteArrayInputStream stream = new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8));
        Response response = resource.importCsvStream(stream, "code", List.of("name"));
        assertEquals(200, response.getStatus());
        assertEquals(5, resource.capturedLines.size());
    }

    /**
     * Tests the chunking logic trigger when the buffer size reaches the limit (1000 lines).
     * Ensures the list is flushed correctly.
     */
    @Test
    void testImportCsvStream_ChunkingTrigger() throws Exception {
        StringBuilder sb = new StringBuilder("code|name\n");
        // Generating exactly 1000 lines (chunk size)
        for (int i = 0; i < 1000; i++) {
            sb.append("CODE").append(i).append("|Name").append(i).append("\n");
        }
        // One extra line that will remain in the final buffer
        sb.append("CODE_LAST|Last Item\n");
        ByteArrayInputStream stream = new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8));
        Response response = resource.importCsvStream(stream, "code", List.of("name"));
        assertEquals(200, response.getStatus());
        String entity = response.getEntity().toString();
        // The simple fact that the test finishes without error validates the truncation logic
        assertTrue(entity.contains("\"createdCount\":1001"));
    }

    /**
     * Tests CSV import when the input stream contains only the header and no data rows.
     */
    @Test
    void testImportCsvStream_OnlyHeader() throws Exception {
        String csvContent = "code|name\n"; // No data
        ByteArrayInputStream stream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        Response response = resource.importCsvStream(stream, "code", List.of("name"));
        assertEquals(200, response.getStatus());
        assertTrue(response.getEntity().toString().contains("\"createdCount\":0"));
    }

    /**
     * Tests the handling of IOExceptions during stream reading.
     * Verifies that a 500 response is returned with the error message.
     */
    @Test
    void testImportCsvStream_IOException() {
        InputStream brokenStream = new InputStream() {
            /**
             * Always fails to simulate an unreadable stream.
             *
             * @return Never returns normally.
             * @throws IOException Always.
             */
            @Override
            public int read() throws IOException {
                throw new IOException("Simulated Read Error");
            }
        };
        Response response = resource.importCsvStream(brokenStream, "code", List.of("name"));
        assertEquals(500, response.getStatus());
        assertTrue(response.getEntity().toString().contains("Error reading file"));
        assertTrue(response.getEntity().toString().contains("Simulated Read Error"));
    }

    // --------------------------------------------------
    // Tests for Transaction Management (withTransaction)
    // --------------------------------------------------

    /**
     * Tests successful transaction execution.
     * Verifies begin, commit are called, and rollback is not.
     */
    @Test
    void testWithTransaction_Success() throws Exception {
        // Mock configuration
        when(transactionManager.getStatus()).thenReturn(jakarta.transaction.Status.STATUS_ACTIVE);
        // Using an array to capture the result via the onSuccess callback
        // because the 'result' field is private in the Executor class
        final String[] capturedResult = new String[1];
        resource.withTransaction(() -> "Success")
                .onSuccess(result -> capturedResult[0] = result);
        assertEquals("Success", capturedResult[0]);
        verify(transactionManager).begin();
        verify(transactionManager).commit();
        verify(transactionManager, never()).rollback();
    }

    /**
     * Tests transaction rollback on exception.
     * Verifies begin and rollback are called, commit is not.
     */
    @Test
    void testWithTransaction_Rollback() throws Exception {
        // Mock configuration
        when(transactionManager.getStatus()).thenReturn(jakarta.transaction.Status.STATUS_ACTIVE);
        RuntimeException expectedEx = new RuntimeException("DB Error");
        // Using an array to capture the exception via the onFailure callback
        final Throwable[] capturedEx = new Throwable[1];
        resource.withTransaction(() -> {
            throw expectedEx;
        }).onFailure(ex -> capturedEx[0] = ex);
        assertEquals(expectedEx, capturedEx[0]);
        verify(transactionManager).begin();
        verify(transactionManager, never()).commit();
        verify(transactionManager).rollback();
        verify(Panache.getEntityManager()).clear();
    }

    /**
     * Tests the scenario where the rollback operation itself throws an exception.
     * Ensures the original exception is preserved.
     */
    @Test
    void testWithTransaction_RollbackFailure() throws Exception {
        // Simulating a failing rollback
        when(transactionManager.getStatus()).thenReturn(jakarta.transaction.Status.STATUS_ACTIVE);
        doThrow(new RuntimeException("Rollback DB Crash")).when(transactionManager).rollback();
        RuntimeException expectedEx = new RuntimeException("Original Error");
        final Throwable[] capturedEx = new Throwable[1];
        // The call must not crash, the rollback exception must be swallowed (logged)
        // but the original exception must be preserved in the Executor
        resource.withTransaction(() -> {
            throw expectedEx;
        }).onFailure(ex -> capturedEx[0] = ex);
        // We verify that we have the original error, not the rollback error
        assertEquals(expectedEx, capturedEx[0]);
        verify(transactionManager).rollback();
    }

    /**
     * Tests the rollback logic when the transaction status is NO_TRANSACTION.
     * Verifies that rollback is skipped.
     */
    @Test
    void testWithTransaction_SkipRollback() throws Exception {
        when(transactionManager.getStatus()).thenReturn(jakarta.transaction.Status.STATUS_NO_TRANSACTION);
        RuntimeException expectedEx = new RuntimeException("Error");
        final Throwable[] capturedEx = new Throwable[1];
        resource.withTransaction(() -> {
            throw expectedEx;
        }).onFailure(ex -> capturedEx[0] = ex);
        assertEquals(expectedEx, capturedEx[0]);
        // Verifies that rollback was never called because the status was not active
        verify(transactionManager, never()).rollback();
    }

    // --------------------------------------------------
    // Tests for Staged Fallback (processWithStages)
    // --------------------------------------------------

    /**
     * Tests the staged processing with a successful chunk.
     * Verifies counters are incremented.
     */
    @Test
    void testProcessWithStages_SimpleSuccess() {
        List<ImporterCsvResource.LineData> lines = createLineDataList(2);
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();
        resource.processWithStages(lines, new HashMap<>(), 1000, counters, errors);
        assertEquals(2, counters[0]);
        assertTrue(errors.isEmpty());
    }

    /**
     * Tests the fallback mechanism when a chunk fails.
     * Verifies that processing is retried with smaller chunk sizes.
     */
    @Test
    void testProcessWithStages_FallbackMechanism() {
        List<ImporterCsvResource.LineData> lines = createLineDataList(3);
        resource.failOnLineCode = "CODE0";
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();
        resource.processWithStages(lines, new HashMap<>(), 2, counters, errors);
        // Verifies that the retry logic was executed multiple times
        assertTrue(resource.processLineLogicCallCount >= 3);
    }

    /**
     * Tests the staged processing method when provided with an empty list of lines.
     * Verifies immediate return without processing.
     */
    @Test
    void testProcessWithStages_EmptyList() {
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();
        // Call with empty list
        resource.processWithStages(Collections.emptyList(), new HashMap<>(), 1000, counters, errors);
        // No counter should have moved, no error
        assertEquals(0, counters[0]);
        assertTrue(errors.isEmpty());
        // We also verify that the business logic was not called
        assertEquals(0, resource.processLineLogicCallCount);
    }

    /**
     * Tests the fallback 1-by-1 processing when {@code findEntityForLine} returns null.
     * Verifies no NullPointerException occurs.
     */
    @Test
    void testProcessLineByLine_NullEntity() {
        List<ImporterCsvResource.LineData> lines = createLineDataList(1);
        // Force return to null via the helper
        resource.returnNullEntity = true;
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();
        // Force chunkSize to 1 to enter processLineByLine
        resource.processWithStages(lines, new HashMap<>(), 1, counters, errors);
        // The test succeeds if no exception is thrown (NPE)
        // because the code contains: if (freshEntity != null) { ... put ... }
        // If null, the map remains empty, but the code does not crash.
        assertTrue(errors.isEmpty());
    }

    // --------------------------------------------------
    // Tests for Utility Parsers
    // --------------------------------------------------

    /**
     * Tests the utility method {@code safeGet} with a known column, an
     * unknown column, and a column beyond the line's cells.
     */
    @Test
    void testSafeGet() {
        ImporterCsvResource.LineData full = line(1, "A", "B");
        assertEquals("A", resource.safeGet(full, "code"));
        assertEquals("B", resource.safeGet(full, "name"));
        // Unknown column (absent from the header)
        assertNull(resource.safeGet(full, "unknown"));
    }

    /**
     * Tests {@code safeParseInt} with valid integers, invalid strings, and empty strings.
     */
    @Test
    void testSafeParseInt() {
        assertEquals(123, resource.safeParseInt(line(1, "123"), "code"));
        assertNull(resource.safeParseInt(line(1, "abc"), "code"));
        assertNull(resource.safeParseInt(line(1, ""), "code"));
    }

    /**
     * Tests {@code safeParseBigDecimal} with valid numbers and invalid formats.
     */
    @Test
    void testSafeParseBigDecimal() {
        assertEquals(new BigDecimal("10.50"), resource.safeParseBigDecimal(line(1, "10.50"), "code"));
        assertNull(resource.safeParseBigDecimal(line(1, "not_a_number"), "code"));
    }

    /**
     * Tests {@code safeParseDateTime} with valid ISO format and invalid formats.
     */
    @Test
    void testSafeParseDateTime() {
        String isoDate = "2023-10-27T10:00:00";
        LocalDateTime expected = LocalDateTime.parse(isoDate, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        assertEquals(expected, resource.safeParseDateTime(line(1, isoDate), "code"));
        assertNull(resource.safeParseDateTime(line(1, "27/10/2023"), "code"));
    }

    /**
     * Tests parsing comma-separated codes into a sorted list.
     */
    @Test
    void testParseCodes() {
        List<String> result = resource.parseCodes("A, B, C");
        assertEquals(Arrays.asList("A", "B", "C"), result);
        List<String> empty = resource.parseCodes(null);
        assertTrue(empty.isEmpty());
        List<String> spaces = resource.parseCodes("  ,  , ");
        assertTrue(spaces.isEmpty());
    }

    /**
     * Tests {@code safeGet} specifically for the short-line boundary: the
     * column exists in the header but the line has no cell at its index.
     */
    @Test
    void testSafeGet_Bounds() {
        ImporterCsvResource.LineData shortLine = line(1, "A");
        assertEquals("A", resource.safeGet(shortLine, "code"));
        // "name" is declared at index 1 but the line only has one cell
        assertNull(resource.safeGet(shortLine, "name"));
    }

    /**
     * Tests {@code safeParseInt} for a missing cell and empty string values.
     */
    @Test
    void testSafeParseInt_BoundsAndEmpty() {
        assertEquals(123, resource.safeParseInt(line(1, "123"), "code"));
        // Case cell beyond the line's cells (resolved to null)
        assertNull(resource.safeParseInt(line(1, "123"), "name"));
        // Case val.isEmpty() (true)
        assertNull(resource.safeParseInt(line(1, ""), "code"));
    }

    /**
     * Tests {@code safeParseBigDecimal} for a missing cell and empty string values.
     */
    @Test
    void testSafeParseBigDecimal_BoundsAndEmpty() {
        assertEquals(new BigDecimal("10.50"), resource.safeParseBigDecimal(line(1, "10.50"), "code"));
        // Case cell beyond the line's cells (resolved to null)
        assertNull(resource.safeParseBigDecimal(line(1, "10.50"), "name"));
        // Case val.isEmpty() (true)
        assertNull(resource.safeParseBigDecimal(line(1, ""), "code"));
    }

    /**
     * Tests {@code safeParseDateTime} for a missing cell and empty string values.
     */
    @Test
    void testSafeParseDateTime_BoundsAndEmpty() {
        String isoDate = "2023-10-27T10:00:00";
        assertNotNull(resource.safeParseDateTime(line(1, isoDate), "code"));
        // Case cell beyond the line's cells (resolved to null)
        assertNull(resource.safeParseDateTime(line(1, isoDate), "name"));
        // Case val.isEmpty() (true)
        assertNull(resource.safeParseDateTime(line(1, ""), "code"));
    }

    /**
     * Tests {@code parseCodes} with null input and whitespace-only input.
     */
    @Test
    void testParseCodes_NullAndEmpty() {
        // Case raw == null or raw.trim().isEmpty()
        assertTrue(resource.parseCodes(null).isEmpty());
        assertTrue(resource.parseCodes("   ").isEmpty());
        List<String> result = resource.parseCodes("A, B, C");
        assertEquals(Arrays.asList("A", "B", "C"), result);
    }

    /**
     * Tests the logic for determining the next smaller chunk size in the fallback algorithm.
     */
    @Test
    void testGetNextSize() {
        assertEquals(ImporterCsvResource.STAGE_2_SIZE, resource.getNextSize(1000));
        assertEquals(ImporterCsvResource.STAGE_3_SIZE, resource.getNextSize(100));
        assertEquals(1, resource.getNextSize(10));
        assertEquals(1, resource.getNextSize(1));
    }

    /**
     * Tests {@code safeParseBoolean} including the case where the cell is missing.
     */
    @Test
    void testSafeParseBoolean() {
        assertTrue(resource.safeParseBoolean(line(1, "true"), "code"));
        assertFalse(resource.safeParseBoolean(line(1, "false"), "code"));
        assertFalse(resource.safeParseBoolean(line(1, ""), "code"));
        // Case cell beyond the line's cells (resolved to null)
        assertFalse(resource.safeParseBoolean(line(1, "true"), "name"));
        // Case unknown column
        assertFalse(resource.safeParseBoolean(line(1, "true"), "unknown"));
    }

    /**
     * Tests {@code safeParseDouble} with valid values, a missing cell, empty values, and invalid formats.
     */
    @Test
    void testSafeParseDouble() {
        // Valid case
        assertEquals(12.5, resource.safeParseDouble(line(1, "12.5"), "code"));
        // Case cell beyond the line's cells (resolved to null)
        assertNull(resource.safeParseDouble(line(1, "12.5"), "name"));
        // Case val.isEmpty() (true)
        assertNull(resource.safeParseDouble(line(1, ""), "code"));
        // Case NumberFormatException
        assertNull(resource.safeParseDouble(line(1, "not_a_double"), "code"));
    }

    /**
     * Tests {@code safeGet} specifically with null elements and trimming.
     * Complements the basic test by checking null handling inside the cells and whitespace.
     */
    @Test
    void testSafeGet_AdvancedCases() {
        // 1. Test Trim behavior
        ImporterCsvResource.LineData withSpaces = line(1, "  Data  ", " Middle ");
        assertEquals("Data", resource.safeGet(withSpaces, "code"), "Should return trimmed value");
        assertEquals("Middle", resource.safeGet(withSpaces, "name"), "Should return trimmed value");

        // 2. Test Null element inside the cells (Edge Case)
        // Depending on CSV parsing logic, an array can technically contain nulls
        ImporterCsvResource.LineData withNull = line(1, "A", null);
        assertEquals("A", resource.safeGet(withNull, "code"));
        assertNull(resource.safeGet(withNull, "name"), "Should handle null element gracefully by returning null");

        // 3. Test unknown column (Safety check)
        assertNull(resource.safeGet(withNull, "unknown"), "Unknown column should return null");
    }

    // --------------------------------------------------
    // Helper / Test Double Class
    // --------------------------------------------------

    /**
     * Concrete implementation of ImporterCsvResource used for testing.
     * Overrides abstract methods to capture execution data.
     */
    static class TestImporter extends ImporterCsvResource {
        /** Captured lines from the processing logic. */
        public List<LineData> capturedLines = new ArrayList<>();

        /** Counter for how many times the processing logic was called. */
        public int processLineLogicCallCount = 0;

        /** If set, simulates a failure for this specific line code. */
        public String failOnLineCode = null;

        /** If true, simulates a null entity return. */
        public boolean returnNullEntity = false;

        /** If true, simulates a generic RuntimeException in processChunkWithFallback. */
        public boolean throwGenericException = false;

        /**
         * Captures the parsed lines of the chunk, or throws when configured to
         * simulate a generic failure.
         *
         * @param parsedLines The list of data for the current chunk.
         * @param targetCodes The set of unique codes in this chunk.
         * @param counters    The global counters [created, updated].
         * @param errors      The error accumulator.
         * @return An empty context map.
         */
        @Override
        protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
            // NEW LOGIC: Simulate a generic error to test the catch (Throwable e) block
            if (throwGenericException) {
                throw new RuntimeException("Simulated Generic Exception for Testing Catch Throwable");
            }

            capturedLines.addAll(parsedLines);
            return new HashMap<>();
        }

        /**
         * Counts the invocation, throws for the configured poison code, and
         * otherwise records a creation.
         *
         * @param data      The parsed line.
         * @param entityMap The pre-fetched or fresh entity map.
         * @param counters  The local counters [created, updated].
         */
        @Override
        protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
            processLineLogicCallCount++;
            if (failOnLineCode != null && failOnLineCode.equals(data.code)) {
                throw new RuntimeException("Simulated failure for " + data.code);
            }
            counters[0]++;
        }

        /**
         * Returns a synthetic entity for the line, or null when configured to
         * simulate a missing entity.
         *
         * @param data The parsed line.
         * @return The synthetic entity or null.
         */
        @Override
        protected Object findEntityForLine(LineData data) {
            // Simulation of the null case
            if (returnNullEntity) {
                return null;
            }
            return "Entity-" + data.code;
        }
    }

    /**
     * Helper method to generate a list of header-bound LineData objects for
     * testing purposes.
     *
     * @param count The number of lines to generate.
     * @return A list of LineData objects.
     */
    private List<ImporterCsvResource.LineData> createLineDataList(int count) {
        List<ImporterCsvResource.LineData> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String code = "CODE" + i;
            list.add(line(i + 2, code, "Name" + i));
        }
        return list;
    }
}
