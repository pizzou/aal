package com.logiplatform.tenancy;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DataSource for authentication and other pre-JWT single-tenant operations.
 *
 * PostgreSQL RLS is deliberately kept enabled. Every borrowed connection gets
 * the configured AAL tenant before the caller can issue a query, and the
 * tenant setting is cleared again when the connection is returned to the
 * underlying pool.
 */
public final class FixedTenantDataSource extends DelegatingDataSource {

    private static final String SET_TENANT_SQL =
            "SELECT set_config('app.current_tenant', ?, false)";

    private final UUID tenantId;

    public FixedTenantDataSource(DataSource targetDataSource, UUID tenantId) {
        super(targetDataSource);
        if (tenantId == null) {
            throw new IllegalArgumentException("Fixed tenant id is required");
        }
        this.tenantId = tenantId;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return prepare(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password)
            throws SQLException {
        return prepare(super.getConnection(username, password));
    }

    private Connection prepare(Connection connection) throws SQLException {
        try {
            setTenant(connection, tenantId.toString());
            return wrapConnection(connection);
        } catch (SQLException | RuntimeException ex) {
            closeQuietly(connection, ex);
            throw ex;
        }
    }

    private void setTenant(Connection connection, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SET_TENANT_SQL)) {
            statement.setString(1, value);
            statement.execute();
        }
    }

    private Connection wrapConnection(Connection delegate) {
        AtomicBoolean closed = new AtomicBoolean(false);

        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if ("close".equals(method.getName()) && method.getParameterCount() == 0) {
                    if (!closed.compareAndSet(false, true)) {
                        return null;
                    }

                    SQLException resetFailure = null;
                    try {
                        setTenant(delegate, "");
                    } catch (SQLException ex) {
                        resetFailure = ex;
                    }

                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException ex) {
                        Throwable target = ex.getCause();
                        if (resetFailure != null) {
                            target.addSuppressed(resetFailure);
                        }
                        throw target;
                    } catch (IllegalAccessException ex) {
                        if (resetFailure != null) {
                            ex.addSuppressed(resetFailure);
                        }
                        throw ex;
                    }
                }

                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException ex) {
                    throw ex.getCause();
                }
            }
        };

        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                handler);
    }

    private static void closeQuietly(Connection connection, Throwable failure) {
        try {
            connection.close();
        } catch (SQLException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
