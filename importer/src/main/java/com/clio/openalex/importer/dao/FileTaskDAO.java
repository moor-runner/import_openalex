package com.clio.openalex.importer.dao;

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

}
