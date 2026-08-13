package com.clio.openalex.importer.dao;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public class SyncJobDAO {
    private static final String INSERT_JOB = """
            INSERT INTO sync_job (entity, snapshot, created_at)
            VALUES (?, ?, CURRENT_TIMESTAMP)
            """;
    private static final String INSERT_FILE_TASK = """
            INSERT IGNORE INTO file_task
                (job_id, status, url, entity, date, part, record_count, read_count)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String PENDING = "PENDING";

    private final JdbcConnections.ConnectionProvider connectionProvider;

    public SyncJobDAO() {
        this(JdbcConnections::open);
    }

    /** 允许测试或嵌入式调用方提供连接池。 */
    public SyncJobDAO(DataSource dataSource) {
        this(Objects.requireNonNull(dataSource, "dataSource")::getConnection);
    }

    private SyncJobDAO(JdbcConnections.ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    /**
     * 在同一连接、同一事务中创建sync job及其file task。
     * file task使用INSERT IGNORE，由(entity,date,part)唯一键提供幂等性。
     *
     * @return 新建sync job的generated job_id
     */
    public long insertJobAndTasks(
            String entity, LocalDate snapshot, List<PendingFileTask> tasks) {
        validate(entity, snapshot, tasks);

        try (Connection connection = connectionProvider.open()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            boolean transactionEnded = false;
            Throwable operationFailure = null;
            try {
                long jobId = insertJob(connection, entity, snapshot);
                insertTasks(connection, jobId, entity, tasks);
                connection.commit();
                transactionEnded = true;
                return jobId;
            } catch (SQLException | RuntimeException | Error e) {
                operationFailure = e;
                transactionEnded = rollback(connection, e);
                throw e;
            } finally {
                // setAutoCommit(true)会隐式提交未结束的事务；rollback失败时绝不能调用。
                if (transactionEnded) {
                    restoreAutoCommit(connection, originalAutoCommit, operationFailure);
                }
            }
        } catch (SQLException e) {
            throw new DataAccessException(
                    "原子写入sync job和file task失败: entity=" + entity, e);
        }
    }

    private static long insertJob(
            Connection connection, String entity, LocalDate snapshot) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                INSERT_JOB, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, entity);
            statement.setDate(2, Date.valueOf(snapshot));
            int rows = statement.executeUpdate();
            if (rows != 1) {
                throw new SQLException("插入sync job的影响行数不是1: " + rows);
            }
            try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                if (!generatedKeys.next()) {
                    throw new SQLException("插入sync job后未返回generated job_id");
                }
                long jobId = generatedKeys.getLong(1);
                if (generatedKeys.wasNull()) {
                    throw new SQLException("generated job_id为NULL");
                }
                return jobId;
            }
        }
    }

    private static void insertTasks(
            Connection connection,
            long jobId,
            String entity,
            List<PendingFileTask> tasks) throws SQLException {
        if (tasks.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(INSERT_FILE_TASK)) {
            for (PendingFileTask task : tasks) {
                statement.setLong(1, jobId);
                statement.setString(2, PENDING);
                statement.setString(3, task.url());
                statement.setString(4, entity);
                statement.setDate(5, Date.valueOf(task.date()));
                statement.setInt(6, task.part());
                statement.setLong(7, task.recordCount());
                statement.setLong(8, 0L);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void validate(
            String entity, LocalDate snapshot, List<PendingFileTask> tasks) {
        if (entity == null || entity.isBlank()) {
            throw new IllegalArgumentException("entity不能为空");
        }
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(tasks, "tasks");
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i) == null) {
                throw new IllegalArgumentException("tasks[" + i + "]不能为空");
            }
        }
    }

    private static boolean rollback(Connection connection, Throwable original) {
        try {
            connection.rollback();
            return true;
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
            return false;
        }
    }

    private static void restoreAutoCommit(
            Connection connection, boolean autoCommit, Throwable operationFailure)
            throws SQLException {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException restoreFailure) {
            if (operationFailure == null) {
                throw restoreFailure;
            }
            operationFailure.addSuppressed(restoreFailure);
        }
    }

    /** DAO层写入契约；状态、entity、jobId和readCount由DAO统一生成。 */
    public record PendingFileTask(
            String url, LocalDate date, int part, long recordCount) {
        public PendingFileTask {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("url不能为空");
            }
            Objects.requireNonNull(date, "date");
            if (part < 0) {
                throw new IllegalArgumentException("part不能为负数: " + part);
            }
            if (recordCount < 0) {
                throw new IllegalArgumentException(
                        "recordCount不能为负数: " + recordCount);
            }
        }
    }
}
