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
import java.util.concurrent.atomic.AtomicBoolean;

public class TenantAwareDataSource extends DelegatingDataSource {

    private static final String SET_TENANT_SQL = "SELECT set_config('app.current_tenant', ?, false)";

    public TenantAwareDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return prepareTenantConnection(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password)
            throws SQLException {

        return prepareTenantConnection(
                super.getConnection(username, password));
    }

    private Connection prepareTenantConnection(Connection connection)
            throws SQLException {

        try {
            applyTenant(connection);
            return wrapConnection(connection);
        } catch (SQLException | RuntimeException ex) {
            try {
                connection.close();
            } catch (SQLException closeException) {
                ex.addSuppressed(closeException);
            }
            throw ex;
        }
    }

    /**
     * Sets the tenant for the lifetime of the borrowed pooled connection.
     *
     * The value is explicitly cleared when the connection is closed so a
     * subsequent borrower of the same physical connection cannot inherit
     * another tenant's context.
     */
    private void applyTenant(Connection connection) throws SQLException {
        String tenantId = TenantContext.isSet()
                ? TenantContext.getTenantId().toString()
                : "";

        try (PreparedStatement statement = connection.prepareStatement(SET_TENANT_SQL)) {

            statement.setString(1, tenantId);
            statement.execute();
        }
    }

    private void clearTenant(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SET_TENANT_SQL)) {

            statement.setString(1, "");
            statement.execute();
        }
    }

    private Connection wrapConnection(Connection delegate) {

        AtomicBoolean closed = new AtomicBoolean(false);

        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(
                    Object proxy,
                    Method method,
                    Object[] args) throws Throwable {

                if ("close".equals(method.getName())
                        && method.getParameterCount() == 0) {

                    if (closed.compareAndSet(false, true)) {
                        SQLException resetFailure = null;

                        try {
                            clearTenant(delegate);
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
                            throw ex;
                        } finally {
                            if (resetFailure != null) {
                                /*
                                 * The delegate connection is still closed in
                                 * the finally path above. The reset exception
                                 * is surfaced when there was no close failure.
                                 */
                            }
                        }
                    }

                    return null;
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
}