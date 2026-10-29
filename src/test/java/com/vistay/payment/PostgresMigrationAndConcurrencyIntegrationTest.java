package com.vistay.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostgresMigrationAndConcurrencyIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private static UUID chargeId1;
    private static UUID financeReqId1;

    @BeforeAll
    static void setUpAll() {
        String envUrl = System.getenv("POSTGRES_TEST_JDBC_URL");
        String sysUrl = System.getProperty("POSTGRES_TEST_JDBC_URL");

        if (sysUrl != null && !sysUrl.isBlank()) {
            jdbcUrl = sysUrl;
            username = System.getProperty("POSTGRES_TEST_USERNAME", "vistay");
            password = System.getProperty("POSTGRES_TEST_PASSWORD", "vistay");
        } else if (envUrl != null && !envUrl.isBlank()) {
            jdbcUrl = envUrl;
            username = System.getenv().getOrDefault("POSTGRES_TEST_USERNAME", "vistay");
            password = System.getenv().getOrDefault("POSTGRES_TEST_PASSWORD", "vistay");
        } else if (isReachable("127.0.0.1", 5433)) {
            jdbcUrl = "jdbc:postgresql://127.0.0.1:5433/vistay_payment_test";
            username = "vistay";
            password = "vistay";
        } else {
            postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("vistay_payment_test")
                    .withUsername("vistay")
                    .withPassword("vistay");
            postgres.start();
            jdbcUrl = postgres.getJdbcUrl();
            username = postgres.getUsername();
            password = postgres.getPassword();
        }
    }

    private static boolean isReachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @AfterAll
    static void tearDownAll() {
        if (postgres != null && postgres.isRunning()) {
            postgres.stop();
        }
    }

    private Connection createConnection(String dbName) throws SQLException {
        String url = jdbcUrl;
        if (dbName != null) {
            int lastSlash = url.lastIndexOf('/');
            int question = url.indexOf('?');
            String params = question >= 0 ? url.substring(question) : "";
            url = url.substring(0, lastSlash + 1) + dbName + params;
        }
        return DriverManager.getConnection(url, username, password);
    }

    @Test
    @Order(1)
    @DisplayName("V1 -> V5 Flyway migration executes cleanly on fresh PostgreSQL")
    void flywayMigration_freshPostgres_executesSuccessfully() {
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .schemas("app")
                .defaultSchema("app")
                .cleanDisabled(false)
                .load();

        flyway.clean();
        MigrateResult result = flyway.migrate();
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(5);
        assertThat(result.targetSchemaVersion).isEqualTo("5");
    }

    @Test
    @Order(2)
    @DisplayName("PostgreSQL enforces unique finance_payment_request_id on booking_charges (uq_booking_charges_finance_request)")
    void postgresEnforces_uniqueFinancePaymentRequestId() throws Exception {
        chargeId1 = UUID.randomUUID();
        financeReqId1 = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        try (Connection conn = createConnection(null)) {
            conn.setAutoCommit(true);
            try (Statement st = conn.createStatement()) {
                st.execute("SET search_path TO app, extensions, public");
            }

            // Insert initial charge
            String sql = """
                INSERT INTO booking_charges (
                    id, charge_code, finance_payment_request_id, booking_id, customer_id,
                    charge_type, original_amount, paid_amount, waived_amount, voided_amount,
                    outstanding_balance, currency, due_date, status, idempotency_key, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 0, ?, 'VND', ?, 'OPEN', ?, 0)
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, chargeId1);
                ps.setString(2, "CHG-TEST-001");
                ps.setObject(3, financeReqId1);
                ps.setObject(4, bookingId);
                ps.setObject(5, customerId);
                ps.setString(6, "DEPOSIT");
                ps.setBigDecimal(7, new BigDecimal("1000000.0000"));
                ps.setBigDecimal(8, new BigDecimal("1000000.0000"));
                ps.setTimestamp(9, Timestamp.from(Instant.now()));
                ps.setString(10, "IDEM-001");
                ps.executeUpdate();
            }

            // Attempt to insert second charge with duplicate finance_payment_request_id
            UUID chargeId2 = UUID.randomUUID();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, chargeId2);
                ps.setString(2, "CHG-TEST-002");
                ps.setObject(3, financeReqId1); // DUPLICATE finance payment request
                ps.setObject(4, UUID.randomUUID());
                ps.setObject(5, UUID.randomUUID());
                ps.setString(6, "DEPOSIT");
                ps.setBigDecimal(7, new BigDecimal("500000.0000"));
                ps.setBigDecimal(8, new BigDecimal("500000.0000"));
                ps.setTimestamp(9, Timestamp.from(Instant.now()));
                ps.setString(10, "IDEM-002");

                assertThatThrownBy(ps::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .satisfies(ex -> {
                            SQLException sqlEx = (SQLException) ex;
                            assertThat(sqlEx.getSQLState()).isEqualTo("23505"); // unique_violation
                        });
            }
        }
    }

    @Test
    @Order(3)
    @DisplayName("PostgreSQL enforces unique finance_allocation_id on booking_charge_allocations")
    void postgresEnforces_uniqueFinanceAllocationId() throws Exception {
        UUID financeAllocId = UUID.randomUUID();
        UUID txId = UUID.randomUUID();

        try (Connection conn = createConnection(null)) {
            conn.setAutoCommit(true);
            try (Statement st = conn.createStatement()) {
                st.execute("SET search_path TO app, extensions, public");
            }

            String sql = """
                INSERT INTO booking_charge_allocations (
                    id, charge_id, finance_allocation_id, payment_transaction_id,
                    allocated_amount, currency, note
                ) VALUES (?, ?, ?, ?, ?, 'VND', ?)
                """;

            // First allocation succeeds
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, chargeId1);
                ps.setObject(3, financeAllocId);
                ps.setObject(4, txId);
                ps.setBigDecimal(5, new BigDecimal("200000.0000"));
                ps.setString(6, "Initial allocation");
                ps.executeUpdate();
            }

            // Duplicate allocation must fail with unique_violation (23505)
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, chargeId1);
                ps.setObject(3, financeAllocId); // DUPLICATE finance allocation ID
                ps.setObject(4, txId);
                ps.setBigDecimal(5, new BigDecimal("200000.0000"));
                ps.setString(6, "Replay duplicate allocation");

                assertThatThrownBy(ps::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .satisfies(ex -> {
                            SQLException sqlEx = (SQLException) ex;
                            assertThat(sqlEx.getSQLState()).isEqualTo("23505"); // unique_violation
                        });
            }
        }
    }

    @Test
    @Order(4)
    @DisplayName("PostgreSQL enforces ck_charge_amounts accounting invariant")
    void postgresEnforces_chargeAmountsInvariant() throws Exception {
        try (Connection conn = createConnection(null)) {
            conn.setAutoCommit(true);
            try (Statement st = conn.createStatement()) {
                st.execute("SET search_path TO app, extensions, public");
            }

            String sql = """
                INSERT INTO booking_charges (
                    id, charge_code, finance_payment_request_id, booking_id, customer_id,
                    charge_type, original_amount, paid_amount, waived_amount, voided_amount,
                    outstanding_balance, currency, due_date, status, idempotency_key, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, 'VND', ?, 'OPEN', ?, 0)
                """;

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, UUID.randomUUID());
                ps.setString(2, "CHG-INVALID-MATH");
                ps.setObject(3, UUID.randomUUID());
                ps.setObject(4, UUID.randomUUID());
                ps.setObject(5, UUID.randomUUID());
                ps.setString(6, "DEPOSIT");
                ps.setBigDecimal(7, new BigDecimal("1000000.0000")); // original
                ps.setBigDecimal(8, new BigDecimal("200000.0000"));  // paid
                ps.setBigDecimal(9, new BigDecimal("700000.0000"));  // outstanding (200k + 700k != 1000k)
                ps.setTimestamp(10, Timestamp.from(Instant.now()));
                ps.setString(11, "IDEM-INVALID-MATH");

                assertThatThrownBy(ps::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .satisfies(ex -> {
                            SQLException sqlEx = (SQLException) ex;
                            assertThat(sqlEx.getSQLState()).isEqualTo("23514"); // check_violation
                        });
            }
        }
    }

    @Test
    @Order(5)
    @DisplayName("Flyway V5 Fail-Safe: Aborts with explicit error if legacy row has NULL finance_payment_request_id")
    void flywayV5_haltsSafely_whenLegacyNullFinanceRequestIdExists() throws Exception {
        // Create an isolated fresh database in PostgreSQL
        try (Connection rootConn = createConnection(null)) {
            rootConn.setAutoCommit(true);
            try (Statement st = rootConn.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS vistay_migration_abort_test WITH (FORCE)");
                st.execute("CREATE DATABASE vistay_migration_abort_test");
            }
        }

        String abortDbUrl = jdbcUrl.replace("vistay_payment_test", "vistay_migration_abort_test");

        // 1. Run migrations only up to V4
        Flyway flywayV4 = Flyway.configure()
                .dataSource(abortDbUrl, username, password)
                .schemas("app")
                .defaultSchema("app")
                .target("4")
                .load();

        MigrateResult resultV4 = flywayV4.migrate();
        assertThat(resultV4.success).isTrue();
        assertThat(resultV4.targetSchemaVersion).isEqualTo("4");

        // 2. Insert a legacy row with finance_payment_request_id = NULL
        try (Connection conn = createConnection("vistay_migration_abort_test")) {
            conn.setAutoCommit(true);
            try (Statement st = conn.createStatement()) {
                st.execute("SET search_path TO app, extensions, public");
            }

            String sql = """
                INSERT INTO booking_charges (
                    id, charge_code, finance_payment_request_id, booking_id, customer_id,
                    charge_type, original_amount, paid_amount, waived_amount, voided_amount,
                    outstanding_balance, currency, due_date, status, idempotency_key, version
                ) VALUES (?, ?, NULL, ?, ?, 'DEPOSIT', 1000000, 0, 0, 0, 1000000, 'VND', now(), 'OPEN', 'IDEM-LEGACY-001', 0)
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, UUID.randomUUID());
                ps.setString(2, "CHG-LEGACY-001");
                ps.setObject(3, UUID.randomUUID());
                ps.setObject(4, UUID.randomUUID());
                ps.executeUpdate();
            }
        }

        // 3. Attempt migration to V5 - must fail-safe abort
        Flyway flywayV5 = Flyway.configure()
                .dataSource(abortDbUrl, username, password)
                .schemas("app")
                .defaultSchema("app")
                .target("5")
                .load();

        assertThatThrownBy(flywayV5::migrate)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("Flyway V5 Migration Halted: Found booking_charges with NULL finance_payment_request_id");
    }

    @Test
    @Order(6)
    @DisplayName("Concurrent allocation transactions: Exactly one succeeds, losers fail with 23505, winner is cleanly queryable")
    void concurrentAllocationTransactions_raceHandledSafely() throws Exception {
        UUID concurrentFinanceAllocId = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger uniqueViolationCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    try (Connection conn = createConnection(null)) {
                        conn.setAutoCommit(false);
                        try (Statement st = conn.createStatement()) {
                            st.execute("SET search_path TO app, extensions, public");
                        }
                        String sql = """
                            INSERT INTO booking_charge_allocations (
                                id, charge_id, finance_allocation_id, payment_transaction_id,
                                allocated_amount, currency, note
                            ) VALUES (?, ?, ?, ?, ?, 'VND', ?)
                            """;
                        try (PreparedStatement ps = conn.prepareStatement(sql)) {
                            ps.setObject(1, UUID.randomUUID());
                            ps.setObject(2, chargeId1);
                            ps.setObject(3, concurrentFinanceAllocId);
                            ps.setObject(4, txId);
                            ps.setBigDecimal(5, new BigDecimal("100000.0000"));
                            ps.setString(6, "Concurrent allocation test");
                            ps.executeUpdate();
                            conn.commit();
                            successCount.incrementAndGet();
                        } catch (SQLException e) {
                            conn.rollback();
                            if ("23505".equals(e.getSQLState())) {
                                uniqueViolationCount.incrementAndGet();
                            } else {
                                throw e;
                            }
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(uniqueViolationCount.get()).isEqualTo(threadCount - 1);

        // Verify recovery read: clean transaction can query winner
        try (Connection conn = createConnection(null)) {
            conn.setAutoCommit(true);
            try (Statement st = conn.createStatement()) {
                st.execute("SET search_path TO app, extensions, public");
            }
            String query = "SELECT COUNT(*) FROM booking_charge_allocations WHERE finance_allocation_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setObject(1, concurrentFinanceAllocId);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
            }
        }
    }

    @Test
    @Order(7)
    @DisplayName("Concurrent distinct allocations on same BookingCharge: Optimistic locking version retry prevents lost updates")
    void concurrentDistinctAllocations_optimisticLockingRetry_preventsLostUpdates() throws Exception {
        UUID allocId1 = UUID.randomUUID();
        UUID allocId2 = UUID.randomUUID();
        BigDecimal amount1 = new BigDecimal("150000.0000");
        BigDecimal amount2 = new BigDecimal("250000.0000");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger retryCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();

        // Helper lambda for optimistic update with retry
        java.util.function.BiConsumer<UUID, BigDecimal> applyWithRetry = (allocId, amount) -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                int maxRetries = 5;
                for (int attempt = 1; attempt <= maxRetries; attempt++) {
                    try (Connection conn = createConnection(null)) {
                        conn.setAutoCommit(false);
                        try (Statement st = conn.createStatement()) {
                            st.execute("SET search_path TO app, extensions, public");
                        }

                        // 1. Read current version and balance
                        long currentVersion;
                        try (PreparedStatement readPs = conn.prepareStatement(
                                "SELECT version FROM booking_charges WHERE id = ?")) {
                            readPs.setObject(1, chargeId1);
                            try (java.sql.ResultSet rs = readPs.executeQuery()) {
                                if (!rs.next()) throw new IllegalStateException("Charge not found");
                                currentVersion = rs.getLong(1);
                            }
                        }

                        // 2. Insert allocation
                        String insertAlloc = """
                            INSERT INTO booking_charge_allocations (
                                id, charge_id, finance_allocation_id, payment_transaction_id,
                                allocated_amount, currency, note
                            ) VALUES (?, ?, ?, ?, ?, 'VND', 'Optimistic race test')
                            """;
                        try (PreparedStatement insPs = conn.prepareStatement(insertAlloc)) {
                            insPs.setObject(1, UUID.randomUUID());
                            insPs.setObject(2, chargeId1);
                            insPs.setObject(3, allocId);
                            insPs.setObject(4, UUID.randomUUID());
                            insPs.setBigDecimal(5, amount);
                            insPs.executeUpdate();
                        }

                        // 3. Optimistic update charge with version check
                        String updateCharge = """
                            UPDATE booking_charges
                            SET paid_amount = paid_amount + ?,
                                outstanding_balance = outstanding_balance - ?,
                                version = version + 1,
                                updated_at = now()
                            WHERE id = ? AND version = ?
                            """;
                        int rowsUpdated;
                        try (PreparedStatement updPs = conn.prepareStatement(updateCharge)) {
                            updPs.setBigDecimal(1, amount);
                            updPs.setBigDecimal(2, amount);
                            updPs.setObject(3, chargeId1);
                            updPs.setLong(4, currentVersion);
                            rowsUpdated = updPs.executeUpdate();
                        }

                        if (rowsUpdated == 1) {
                            conn.commit();
                            return;
                        } else {
                            conn.rollback();
                            retryCount.incrementAndGet();
                            Thread.sleep(15);
                        }
                    }
                }
                throw new IllegalStateException("Failed after max optimistic retries");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        futures.add(executor.submit(() -> applyWithRetry.accept(allocId1, amount1)));
        futures.add(executor.submit(() -> applyWithRetry.accept(allocId2, amount2)));

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // Verify final charge state has both payments applied and version incremented twice
        try (Connection conn = createConnection(null)) {
            conn.setAutoCommit(true);
            try (Statement st = conn.createStatement()) {
                st.execute("SET search_path TO app, extensions, public");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT paid_amount, outstanding_balance, version FROM booking_charges WHERE id = ?")) {
                ps.setObject(1, chargeId1);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    BigDecimal paid = rs.getBigDecimal("paid_amount");
                    BigDecimal balance = rs.getBigDecimal("outstanding_balance");
                    long version = rs.getLong("version");

                    // Initial paid was 0; plus 150,000 + 250,000 = 400,000
                    assertThat(paid).isEqualByComparingTo("400000.0000");
                    // Initial balance was 1,000,000; minus 150,000 - 250,000 = 600,000
                    assertThat(balance).isEqualByComparingTo("600000.0000");
                    assertThat(version).isGreaterThanOrEqualTo(2);
                }
            }
        }
    }
}
