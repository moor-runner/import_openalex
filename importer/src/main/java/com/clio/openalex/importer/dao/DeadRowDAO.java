package com.clio.openalex.importer.dao;

import com.clio.openalex.importer.work.DeadRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * 死信行的落库与消费。
 *
 * <p>写入（本类当前实现）：worker 在解析文件时把单行 Poisoned 数据落到 dead_row。
 * 幂等由 (file_id, line_no) 唯一键 + INSERT IGNORE 提供——崩溃/超时后整文件从头重放，
 * 同一物理坏行会再次命中，靠该键收敛成一条，不会越堆越多。
 *
 * <p>消费（reprocess 侧）：批量把 unresolved 死信过一遍 transform、能解的入库并标 resolved，
 * 属于人工把关后触发的动作，后续再补相应查询/更新方法。
 */
public class DeadRowDAO {

    /**
     * 落死信行；参与调用方（commitChunk）事务：只 addBatch/executeBatch，绝不 commit / close 连接。
     * 好行与坏行在同一个 chunk 事务里一起提交或一起回滚——回滚后整批重放，靠唯一键保持幂等。
     * created_at/updated_at 首次落库都取当前时间，后续消费改 status 时由 ON UPDATE 刷新 updated_at。
     */
    private static final String INSERT_IGNORE_DEAD_ROW = """
            INSERT IGNORE INTO dead_row
                (file_id, line_no, raw_line, error_msg, status, entity_type, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """;

    private final JdbcConnections.ConnectionProvider connectionProvider;
    private static final Logger log = LoggerFactory.getLogger(DeadRowDAO.class);

    public DeadRowDAO() {
        this(JdbcConnections::open);
    }

    /**
     * 允许测试或嵌入式调用方提供连接池。
     */
    public DeadRowDAO(DataSource dataSource) {
        this(Objects.requireNonNull(dataSource, "dataSource")::getConnection);
    }

    private DeadRowDAO(JdbcConnections.ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    /**
     * 批量落死信行，参与调用方事务，不提交不关闭连接。
     * 幂等由 (file_id, line_no) 唯一键 + INSERT IGNORE 提供，重放同一文件不会产生重复死信。
     *
     * @param connection 调用方事务内的连接
     * @param rows       本批次检测出的坏行；为空时直接返回
     */
    public void insert(Connection connection, List<DeadRow> rows) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(rows, "rows");
        if (rows.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(INSERT_IGNORE_DEAD_ROW)) {
            for (DeadRow row : rows) {
                statement.setLong(1, row.getFileId());
                statement.setLong(2, row.getLineNo());
                statement.setString(3, row.getRawLine());
                statement.setString(4, row.getErrorMsg());
                statement.setString(5, row.getStatus());
                statement.setString(6, row.getEntityType());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }
}
