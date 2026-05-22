package com.accessflow.e2e;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

// AF-274: minimal java.sql.Driver implementation for the e2e custom-driver
// upload spec. The backend's URLClassLoader probe (DefaultCustomJdbcDriverService)
// only needs to (a) find the class, (b) confirm it implements java.sql.Driver,
// and (c) instantiate it via the no-arg constructor. Nothing here is ever
// asked to open a real connection — the e2e flow never calls
// /api/v1/datasources/{id}/test against this driver.
public class StubDriver implements Driver {

    public StubDriver() {
        // intentionally empty — no DriverManager.registerDriver(...) so the
        // probe-load has zero side effects.
    }

    @Override
    public Connection connect(String url, Properties info) {
        return null;
    }

    @Override
    public boolean acceptsURL(String url) {
        return false;
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 0;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }
}
