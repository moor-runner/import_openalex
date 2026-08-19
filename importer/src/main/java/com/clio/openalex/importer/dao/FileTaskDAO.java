package com.clio.openalex.importer.dao;

import com.clio.openalex.importer.plan.Entity;
import com.clio.openalex.importer.plan.FileTask;
import com.clio.openalex.importer.plan.Planner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

public class FileTaskDAO {
    private static final String SELECT_LATEST = """
            SELECT date, part
              FROM file_task
             WHERE entity = ?
             ORDER BY date DESC, part DESC
             LIMIT 1
            """;

    /** 心跳超时秒数：running 但超过该时长没更新心跳的任务视为僵尸，可被重新领取。 */
    private static final long DEFAULT_TIMEOUT_SECONDS = 300;

    /**
     * 领取一行可执行任务：pending 或 已超时的僵尸（running 但心跳过期）。
     * FOR UPDATE SKIP LOCKED 保证并发下各 worker 各领各的、互不阻塞。
     */
    private static final String SELECT_CLAIMABLE = """
            SELECT file_id, job_id, url, entity, date, part, record_count, read_count
              FROM file_task
             WHERE entity = ?
               AND ( status = 'pending'
                  OR ( status = 'running'
                   AND last_heartbeat < DATE_SUB(NOW(), INTERVAL ? SECOND) ) )
             ORDER BY date, part
             LIMIT 1
             FOR UPDATE SKIP LOCKED
            """;

    private static final String MARK_RUNNING = """
            UPDATE file_task
               SET status = 'running', last_heartbeat = NOW()
             WHERE file_id = ?
            """;

    private static final String UPDATE_PROGRESS = """
            UPDATE file_task
               SET read_count = ?, last_heartbeat = NOW()
             WHERE file_id = ?
            """;

    private static final String UPDATE_STATUS = """
            UPDATE file_task
               SET status = ?
             WHERE file_id = ?
            """;

    private final JdbcConnections.ConnectionProvider connectionProvider;
    private static final Logger log = LoggerFactory.getLogger(Planner.class);

    public FileTaskDAO() {
        this(JdbcConnections::open);
    }

    /**
     * 允许测试或嵌入式调用方提供连接池。
     */
    public FileTaskDAO(DataSource dataSource) {
        this(Objects.requireNonNull(dataSource, "dataSource")::getConnection);
    }

    private FileTaskDAO(JdbcConnections.ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    /**
     * 查询当前entity已经存在的最新file task水位。
     * 水位只由(date, part)确定，不过滤任务状态。
     *
     * @param entity 数据库中使用的entity名称
     * @return 最新任务；当前entity还没有任务时为空
     */
    public Optional<FileTask> selectLatestFileTask(String entity) {
        log.info("开始水位查询");
        if (entity == null || entity.isBlank()) {
            throw new IllegalArgumentException("entity不能为空");
        }

        try (Connection connection = connectionProvider.open();
             PreparedStatement statement = connection.prepareStatement(SELECT_LATEST)) {
            statement.setString(1, entity);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    log.info("水位为空");
                    return Optional.empty();
                }
                Date date = resultSet.getDate("date");
                if (date == null) {
                    throw new SQLException("file task水位date为NULL");
                }
                int part = resultSet.getInt("part");
                if (resultSet.wasNull() || part < 0) {
                    throw new SQLException("file task水位part为空或为负数");
                }
                FileTask task = new FileTask();
                task.setDate(date.toLocalDate());
                task.setPart(part);
                log.info("返回水位");
                return Optional.of(task);
            }
        } catch (SQLException e) {
            throw new DataAccessException("查询file task水位失败: entity=" + entity, e);
        }
    }

    /**
     * 领取一个可执行的file task：pending 或已超时的僵尸（running 但心跳过期）。
     * 用 SELECT ... FOR UPDATE SKIP LOCKED 在毫秒级短事务内选中并置为 running、刷新心跳；
     * 多worker并发时各领各的、互不阻塞。行锁只保护领取本身，导入期间靠 status+心跳占有，不持有锁。
     *
     * @param entity 目标entity
     * @return 领到的任务（已置running）；无可领取任务时为空
     */
    public Optional<FileTask> claim(Entity entity) {
        return claim(entity, DEFAULT_TIMEOUT_SECONDS);
    }

    public Optional<FileTask> claim(Entity entity, long timeoutSeconds) {
        Objects.requireNonNull(entity, "entity");
        try (Connection connection = connectionProvider.open()) {
            connection.setAutoCommit(false);
            try {
                FileTask task = selectClaimable(connection, entity, timeoutSeconds);
                if (task == null) {
                    connection.commit();          // 无任务：正常结束短事务
                    return Optional.empty();
                }
                markRunning(connection, task.getFileId());
                connection.commit();
                task.setStatus("running");
                return Optional.of(task);
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new DataAccessException("领取file task失败: entity=" + entity, e);
        }
    }

    /**
     * 在调用方事务内推进 read_count 并刷新心跳。
     * 参与调用方（commitChunk）的事务，绝不 commit / close 连接。
     */
    public void advanceProgress(Connection connection, long fileId, long readCount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_PROGRESS)) {
            statement.setLong(1, readCount);
            statement.setLong(2, fileId);
            statement.executeUpdate();
        }
    }

    /** 文件导入成功：置为 done。 */
    public void markDone(long fileId) {
        updateStatus(fileId, "done");
    }

    /** 可重试异常：退回 pending，等下一轮或别的 worker 重新领取。 */
    public void markPending(long fileId) {
        updateStatus(fileId, "pending");
    }

    /** 不可恢复异常：置为 failed。 */
    public void markFailed(long fileId) {
        updateStatus(fileId, "failed");
    }

    private void updateStatus(long fileId, String status) {
        try (Connection connection = connectionProvider.open();
             PreparedStatement statement = connection.prepareStatement(UPDATE_STATUS)) {
            statement.setString(1, status);
            statement.setLong(2, fileId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException(
                    "更新file task状态失败: fileId=" + fileId + ", status=" + status, e);
        }
    }

    private static FileTask selectClaimable(
            Connection connection, Entity entity, long timeoutSeconds) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_CLAIMABLE)) {
            statement.setString(1, entity.name());   // 与 SyncJobDAO 写入的 entity 值保持一致
            statement.setLong(2, timeoutSeconds);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return mapRow(resultSet, entity);
            }
        }
    }

    private static void markRunning(Connection connection, long fileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(MARK_RUNNING)) {
            statement.setLong(1, fileId);
            statement.executeUpdate();
        }
    }

    private static FileTask mapRow(ResultSet resultSet, Entity entity) throws SQLException {
        FileTask task = new FileTask();
        task.setFileId(resultSet.getLong("file_id"));
        task.setJobId(resultSet.getLong("job_id"));
        task.setUrl(resultSet.getString("url"));
        task.setEntity(entity);
        Date date = resultSet.getDate("date");
        if (date != null) {
            task.setDate(date.toLocalDate());
        }
        task.setPart(resultSet.getInt("part"));
        task.setRecordCount(resultSet.getLong("record_count"));
        task.setReadCount(resultSet.getLong("read_count"));
        return task;
    }

}
