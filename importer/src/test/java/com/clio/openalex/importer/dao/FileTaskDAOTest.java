package com.clio.openalex.importer.dao;

import com.clio.openalex.importer.plan.FileTask;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileTaskDAOTest {

    @Test
    void latestWatermarkDoesNotFilterStatusAndMapsDateAndPart() {
        AtomicReference<String> preparedSql = new AtomicReference<>();
        AtomicReference<String> boundEntity = new AtomicReference<>();
        AtomicBoolean firstRow = new AtomicBoolean(true);

        ResultSet resultSet = proxy(ResultSet.class, (ignored, method, args) -> switch (method.getName()) {
            case "next" -> firstRow.getAndSet(false);
            case "getDate" -> Date.valueOf(LocalDate.of(2026, 8, 12));
            case "getInt" -> 37;
            case "close" -> null;
            default -> defaultValue(method);
        });
        PreparedStatement statement = proxy(
                PreparedStatement.class,
                (ignored, method, args) -> switch (method.getName()) {
                    case "setString" -> {
                        assertEquals(1, args[0]);
                        boundEntity.set((String) args[1]);
                        yield null;
                    }
                    case "executeQuery" -> resultSet;
                    case "close" -> null;
                    default -> defaultValue(method);
                });
        Connection connection = proxy(Connection.class, (ignored, method, args) -> switch (method.getName()) {
            case "prepareStatement" -> {
                preparedSql.set((String) args[0]);
                yield statement;
            }
            case "close" -> null;
            default -> defaultValue(method);
        });
        DataSource dataSource = proxy(DataSource.class, (ignored, method, args) -> switch (method.getName()) {
            case "getConnection" -> connection;
            default -> defaultValue(method);
        });

        Optional<FileTask> result = new FileTaskDAO(dataSource)
                .selectLatestFileTask("sources");

        assertTrue(result.isPresent());
        assertEquals("sources", boundEntity.get());
        String sql = normalize(preparedSql.get());
        assertFalse(sql.contains("status"), "水位查询不能按status过滤");
        assertTrue(sql.contains("where entity = ?"));
        assertTrue(sql.contains("order by date desc, part desc"));
        assertEquals(LocalDate.of(2026, 8, 12), field(result.orElseThrow(), "date"));
        assertEquals(37, field(result.orElseThrow(), "part"));
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static Object field(FileTask task, String name) {
        try {
            Field field = FileTask.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(task);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
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
