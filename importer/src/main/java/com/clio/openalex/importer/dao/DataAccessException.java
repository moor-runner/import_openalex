package com.clio.openalex.importer.dao;

/** DAO操作失败时使用的非受检异常。 */
public final class DataAccessException extends RuntimeException {
    public DataAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
