package com.vistay.payment;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VietQrActivationConcurrencyIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    @BeforeAll
    static void setUpAll() {
        if (isReachable("127.0.0.1", 5433)) {
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

        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .schemas("app")
                .defaultSchema("app")
                .cleanDisabled(false)
                .locations("classpath:db/migration")
                .load();
        flyway.clean();
        flyway.migrate();
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

    private Connection createConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    @Test
    @DisplayName("Concurrent approval with optimistic locking allows exactly one winner")
    void concurrentApproval_OptimisticLockingAllowsExactlyOneWinner() throws Exception {
        UUID planId = UUID.randomUUID();
        String planCode = "CONC-PLAN-" + UUID.randomUUID().toString().substring(0, 8);
        String creatorId = "ADMIN:CREATOR";

        try (Connection conn = createConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO app.vietqr_activation_plans (
                         id, plan_code, merchant_code, merchant_name, bank_bin, bank_name,
                         bank_account_number, bank_account_name, target_activation_mode, status,
                         created_by_admin_user_id, version
                     ) VALUES (?, ?, 'M01', 'Merchant 01', '970422', 'MB', '123456', 'TEST', 'GENERATE_ONLY', 'PENDING_APPROVAL', ?, 0)
                     """)) {
            ps.setObject(1, planId);
            ps.setString(2, planCode);
            ps.setString(3, creatorId);
            ps.executeUpdate();
        }

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final String checkerId = "ADMIN:CHECKER_" + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    try (Connection conn = createConnection();
                         PreparedStatement ps = conn.prepareStatement("""
                                 UPDATE app.vietqr_activation_plans
                                 SET status = 'APPROVED',
                                     approved_by_admin_user_id = ?,
                                     approved_at = now(),
                                     version = version + 1,
                                     updated_at = now()
                                 WHERE id = ? AND version = 0 AND status = 'PENDING_APPROVAL'
                                 """)) {
                        ps.setString(1, checkerId);
                        ps.setObject(2, planId);
                        int rows = ps.executeUpdate();
                        if (rows == 1) {
                            successCount.incrementAndGet();
                        } else {
                            conflictCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    conflictCount.incrementAndGet();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        finishLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        try (Connection conn = createConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT status, version FROM app.vietqr_activation_plans WHERE id = ?")) {
            ps.setObject(1, planId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("APPROVED");
                assertThat(rs.getLong("version")).isEqualTo(1L);
            }
        }
    }

    @Test
    @DisplayName("Database constraint ck_activation_maker_checker prevents creator from approving their own plan")
    void databaseConstraint_MakerCheckerEnforced() throws Exception {
        UUID planId = UUID.randomUUID();
        String planCode = "MC-PLAN-" + UUID.randomUUID().toString().substring(0, 8);
        String creatorId = "ADMIN:MAKER_CHECKER_USER";

        try (Connection conn = createConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO app.vietqr_activation_plans (
                         id, plan_code, merchant_code, merchant_name, bank_bin, bank_name,
                         bank_account_number, bank_account_name, target_activation_mode, status,
                         created_by_admin_user_id, version
                     ) VALUES (?, ?, 'M02', 'Merchant 02', '970422', 'MB', '123456', 'TEST', 'GENERATE_ONLY', 'PENDING_APPROVAL', ?, 0)
                     """)) {
            ps.setObject(1, planId);
            ps.setString(2, planCode);
            ps.setString(3, creatorId);
            ps.executeUpdate();
        }

        // Attempt to update approved_by_admin_user_id to the exact same creator ID
        try (Connection conn = createConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE app.vietqr_activation_plans
                     SET status = 'APPROVED',
                         approved_by_admin_user_id = ?,
                         approved_at = now()
                     WHERE id = ?
                     """)) {
            ps.setString(1, creatorId);
            ps.setObject(2, planId);
            assertThatThrownBy(ps::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_activation_maker_checker");
        }
    }

    @Test
    @DisplayName("Database constraint ck_callback_uat_ready blocks CALLBACK_ENABLED approval without UAT, secret and HTTPS")
    void databaseConstraint_CallbackUatReadyEnforced() throws Exception {
        UUID planId = UUID.randomUUID();
        String planCode = "UAT-PLAN-" + UUID.randomUUID().toString().substring(0, 8);

        try (Connection conn = createConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO app.vietqr_activation_plans (
                         id, plan_code, merchant_code, merchant_name, bank_bin, bank_name,
                         bank_account_number, bank_account_name, target_activation_mode, status,
                         uat_verified, callback_secret_configured, callback_url,
                         created_by_admin_user_id, version
                     ) VALUES (?, ?, 'M03', 'Merchant 03', '970422', 'MB', '123456', 'TEST', 'CALLBACK_ENABLED', 'PENDING_APPROVAL', false, false, 'http://insecure.url', 'ADMIN:USER1', 0)
                     """)) {
            ps.setObject(1, planId);
            ps.setString(2, planCode);
            ps.executeUpdate();
        }

        // Attempting to transition to APPROVED without fulfilling UAT, secret and HTTPS URL must fail DB check
        try (Connection conn = createConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE app.vietqr_activation_plans
                     SET status = 'APPROVED',
                         approved_by_admin_user_id = 'ADMIN:CHECKER2'
                     WHERE id = ?
                     """)) {
            ps.setObject(1, planId);
            assertThatThrownBy(ps::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_callback_uat_ready");
        }
    }
}
