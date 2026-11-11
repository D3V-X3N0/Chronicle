package io.github.d3vx3n0.chronicle.storage.sqlite;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import org.sqlite.JDBC;

/** Loaded with only the platform parent inside Paper, so the server's older JDBC cannot win.
 * Only JDK interfaces cross this boundary. Remove Xerial's global registration to permit cleanup.
 */
public final class IsolatedDriver {
    private IsolatedDriver() {}
    public static Connection connect(String url) throws SQLException {
        var driver = new JDBC();
        var registered = DriverManager.getDrivers();
        while (registered.hasMoreElements()) {
            var entry = registered.nextElement();
            if (entry.getClass().getClassLoader() == IsolatedDriver.class.getClassLoader()) DriverManager.deregisterDriver(entry);
        }
        return driver.connect(url, new Properties());
    }
}
