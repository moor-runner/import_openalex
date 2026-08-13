package com.clio.openalex.importer.dao;

import com.clio.openalex.importer.plan.Entity;
import com.clio.openalex.importer.plan.FileTask;
import com.clio.openalex.importer.plan.SyncJob;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncJobDAOTest {

    @Test
    void successfulWriteUsesOneTransactionAndBindsGeneratedJobIdAndTaskDefaults() {
        FakeJdbc jdbc = new FakeJdbc(false, 8123L);
        LocalDate snapshot = LocalDate.of(2026, 8, 13);
        List<FileTask> tasks = List.of(
                fileTask("s3://bucket/part_0000.gz",
                        LocalDate.of(2026, 8, 11), 0, 21),
                fileTask("s3://bucket/part_0001.gz",
                        LocalDate.of(2026, 8, 11), 1, 34));

        long jobId = new SyncJobDAO(jdbc.dataSource)
                .insertJobAndTasks(syncJob(snapshot), tasks);

        assertEquals(8123L, jobId);
        assertEquals(1, jdbc.connectionRequests, "整个事务只能获取一个Connection");
        assertEquals(List.of(false, true), jdbc.autoCommitChanges);
        assertEquals(1, jdbc.commits);
        assertEquals(0, jdbc.rollbacks);
        assertEquals("SOURCES", jdbc.jobParameters.get(1));
        assertEquals(Date.valueOf(snapshot), jdbc.jobParameters.get(2));
        assertTrue(normalize(jdbc.jobSql).contains(
                "insert into sync_job (entity, snapshot, created_at)"));
        assertTrue(normalize(jdbc.taskSql).contains("insert ignore into file_task"));
        assertEquals(2, jdbc.taskBatches.size());

        assertTask(jdbc.taskBatches.get(0), 8123L, "PENDING",
                "s3://bucket/part_0000.gz", "SOURCES",
                LocalDate.of(2026, 8, 11), 0, 21L, 0L);
        assertTask(jdbc.taskBatches.get(1), 8123L, "PENDING",
                "s3://bucket/part_0001.gz", "SOURCES",
                LocalDate.of(2026, 8, 11), 1, 34L, 0L);
    }

    @Test
    void batchFailureRollsBackAndNeverCommits() {
        FakeJdbc jdbc = new FakeJdbc(true, 99L);
        SyncJobDAO dao = new SyncJobDAO(jdbc.dataSource);
        List<FileTask> tasks = List.of(fileTask("s3://bucket/part_0000.gz",
                LocalDate.of(2026, 8, 12), 0, 1));

        DataAccessException failure = assertThrows(
                DataAccessException.class,
                () -> dao.insertJobAndTasks(
                        syncJob(LocalDate.of(2026, 8, 13)), tasks));

        assertTrue(failure.getCause() instanceof SQLException);
        assertEquals(1, jdbc.connectionRequests);
        assertEquals(List.of(false, true), jdbc.autoCommitChanges);
        assertEquals(1, jdbc.rollbacks);
        assertEquals(0, jdbc.commits);
    }

    @Test
    void rollbackFailureIsSuppressedAndDoesNotRestoreAutoCommit() {
        FakeJdbc jdbc = new FakeJdbc(true, true, 100L);
        SyncJobDAO dao = new SyncJobDAO(jdbc.dataSource);
        List<FileTask> tasks = List.of(fileTask("s3://bucket/part_0000.gz",
                LocalDate.of(2026, 8, 12), 0, 1));

        DataAccessException failure = assertThrows(
                DataAccessException.class,
                () -> dao.insertJobAndTasks(
                        syncJob(LocalDate.of(2026, 8, 13)), tasks));

        Throwable operationFailure = failure.getCause();
        assertTrue(operationFailure instanceof SQLException);
        assertEquals("simulated batch failure", operationFailure.getMessage());
        assertEquals(1, operationFailure.getSuppressed().length);
        assertEquals("simulated rollback failure",
                operationFailure.getSuppressed()[0].getMessage());
        assertEquals(List.of(false), jdbc.autoCommitChanges,
                "rollback失败后恢复autoCommit可能隐式提交未结束的事务");
        assertEquals(1, jdbc.rollbacks);
        assertEquals(0, jdbc.commits);
    }

    private static void assertTask(
            Map<Integer, Object> parameters,
            long jobId,
            String status,
            String url,
            String entity,
            LocalDate date,
            int part,
            long recordCount,
            long readCount) {
        assertEquals(jobId, parameters.get(1));
        assertEquals(status, parameters.get(2));
        assertEquals(url, parameters.get(3));
        assertEquals(entity, parameters.get(4));
        assertEquals(Date.valueOf(date), parameters.get(5));
        assertEquals(part, parameters.get(6));
        assertEquals(recordCount, parameters.get(7));
        assertEquals(readCount, parameters.get(8));
    }

    private static SyncJob syncJob(LocalDate snapshot) {
        SyncJob syncJob = new SyncJob();
        syncJob.setEntity(Entity.SOURCES);
        syncJob.setSnapshot(snapshot);
        return syncJob;
    }

    private static FileTask fileTask(
            String url, LocalDate date, int part, long recordCount) {
        FileTask task = new FileTask();
        task.setStatus("PENDING");
        task.setUrl(url);
        task.setEntity(Entity.SOURCES);
        task.setDate(date);
        task.setPart(part);
        task.setRecordCount(recordCount);
        task.setReadCount(0L);
        return task;
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase();
    }

    private static final class FakeJdbc {
        private final boolean failBatch;
        private final boolean failRollback;
        private final long generatedJobId;
        private final DataSource dataSource;
        private final List<Boolean> autoCommitChanges = new ArrayList<>();
        private final Map<Integer, Object> jobParameters = new HashMap<>();
        private final Map<Integer, Object> currentTaskParameters = new HashMap<>();
        private final List<Map<Integer, Object>> taskBatches = new ArrayList<>();
        private int connectionRequests;
        private int commits;
        private int rollbacks;
        private boolean generatedKeyAvailable = true;
        private boolean autoCommit = true;
        private String jobSql;
        private String taskSql;

        private FakeJdbc(boolean failBatch, long generatedJobId) {
            this(failBatch, false, generatedJobId);
        }

        private FakeJdbc(boolean failBatch, boolean failRollback, long generatedJobId) {
            this.failBatch = failBatch;
            this.failRollback = failRollback;
            this.generatedJobId = generatedJobId;

            ResultSet generatedKeys = proxy(ResultSet.class, this::generatedKeysCall);
            PreparedStatement jobStatement = proxy(
                    PreparedStatement.class,
                    (ignored, method, args) -> statementCall(
                            method, args, jobParameters, generatedKeys, true));
            PreparedStatement taskStatement = proxy(
                    PreparedStatement.class,
                    (ignored, method, args) -> statementCall(
                            method, args, currentTaskParameters, null, false));
            Connection connection = proxy(
                    Connection.class,
                    (ignored, method, args) -> connectionCall(
                            method, args, jobStatement, taskStatement));
            dataSource = proxy(DataSource.class, (ignored, method, args) -> {
                if (method.getName().equals("getConnection")) {
                    connectionRequests++;
                    return connection;
                }
                return defaultValue(method);
            });
        }

        private Object connectionCall(
                Method method,
                Object[] args,
                PreparedStatement jobStatement,
                PreparedStatement taskStatement) throws SQLException {
            return switch (method.getName()) {
                case "getAutoCommit" -> autoCommit;
                case "setAutoCommit" -> {
                    autoCommit = (Boolean) args[0];
                    autoCommitChanges.add(autoCommit);
                    yield null;
                }
                case "prepareStatement" -> {
                    String sql = (String) args[0];
                    if (sql.contains("sync_job")) {
                        jobSql = sql;
                        yield jobStatement;
                    }
                    if (sql.contains("file_task")) {
                        taskSql = sql;
                        yield taskStatement;
                    }
                    throw new AssertionError("未预期的SQL: " + sql);
                }
                case "commit" -> {
                    commits++;
                    yield null;
                }
                case "rollback" -> {
                    rollbacks++;
                    if (failRollback) {
                        throw new SQLException("simulated rollback failure");
                    }
                    yield null;
                }
                case "close" -> null;
                default -> defaultValue(method);
            };
        }

        private Object statementCall(
                Method method,
                Object[] args,
                Map<Integer, Object> parameters,
                ResultSet generatedKeys,
                boolean jobStatement) throws SQLException {
            String name = method.getName();
            if (name.startsWith("set") && args != null && args.length >= 2
                    && args[0] instanceof Integer index) {
                parameters.put(index, args[1]);
                return null;
            }
            return switch (name) {
                case "executeUpdate" -> 1;
                case "getGeneratedKeys" -> generatedKeys;
                case "addBatch" -> {
                    if (jobStatement) {
                        throw new AssertionError("sync_job不应批量插入");
                    }
                    taskBatches.add(Map.copyOf(parameters));
                    parameters.clear();
                    yield null;
                }
                case "executeBatch" -> {
                    if (failBatch) {
                        throw new SQLException("simulated batch failure");
                    }
                    yield new int[taskBatches.size()];
                }
                case "close" -> null;
                default -> defaultValue(method);
            };
        }

        private Object generatedKeysCall(Object ignored, Method method, Object[] args) {
            return switch (method.getName()) {
                case "next" -> {
                    boolean available = generatedKeyAvailable;
                    generatedKeyAvailable = false;
                    yield available;
                }
                case "getLong" -> generatedJobId;
                case "wasNull" -> false;
                case "close" -> null;
                default -> defaultValue(method);
            };
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Method method) {
        Class<?> type = method.getReturnType();
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
