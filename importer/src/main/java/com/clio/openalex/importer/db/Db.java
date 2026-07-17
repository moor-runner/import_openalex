package com.clio.openalex.importer.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * JDBC 连接工厂。时间契约(C2): DATETIME(3) 全链按 UTC 理解,
 * connectionTimeZone=UTC 固定住 driver 侧换算, 禁止依赖服务器时区。
 * 凭据走环境变量, 不进代码与配置文件。
 */
public final class Db {

    public static Connection connect() throws SQLException {
        String host = env("OPENALEX_MYSQL_HOST", "127.0.0.1");
        String port = env("OPENALEX_MYSQL_PORT", "3306");
        String user = env("OPENALEX_MYSQL_USER", "root");
        String pass = System.getenv("OPENALEX_MYSQL_PASSWORD");
        if (pass == null) {
            throw new IllegalStateException("缺少环境变量 OPENALEX_MYSQL_PASSWORD");
        }
        String url = "jdbc:mysql://" + host + ":" + port
                + "/?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=false"
                + "&rewriteBatchedStatements=false"; // 批量靠手拼 multi-VALUES(契约 C3), 不靠 driver 改写
        return DriverManager.getConnection(url, user, pass);
    }

    /**
     * 导入会话初始化(契约参数表): 联调/初次全量关 binlog(无 CDC 消费者, 省写放大);
     * 月度增量期必须去掉这条 —— CDC 依赖 binlog。
     */
    public static void initImportSession(Connection c, boolean disableBinlog) throws SQLException {
        try (Statement st = c.createStatement()) {
            if (disableBinlog) {
                st.execute("SET SESSION sql_log_bin = 0");
            }
        }
        c.setAutoCommit(false); // 1 批 = 1 事务
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }

    private Db() {}
}
