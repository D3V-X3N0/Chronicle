package io.github.d3vx3n0.chronicle.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.Driver;
import java.util.Properties;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("database")
class PackagedNativeTest {
    @TempDir Path directory;
    @Test void standaloneDistributableRetainsNativeLibrariesNoticesAndLoadsItsOwnDriver() throws Exception {
        Path artifact = Path.of(System.getProperty("chronicle.artifact"));
        try (var jar = new JarFile(artifact.toFile())) {
            assertNotNull(jar.getJarEntry("org/sqlite/native/Windows/x86_64/sqlitejdbc.dll"));
            assertNotNull(jar.getJarEntry("org/sqlite/native/Linux/x86_64/libsqlitejdbc.so"));
            assertNotNull(jar.getJarEntry("META-INF/maven/org.xerial/sqlite-jdbc/LICENSE"));
            assertNotNull(jar.getJarEntry("META-INF/maven/org.xerial/sqlite-jdbc/LICENSE.zentus"));
        }
        try (var loader = new URLClassLoader(new java.net.URL[] {artifact.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            var driverClass = loader.loadClass("org.sqlite.JDBC");
            assertEquals(loader, driverClass.getClassLoader());
            var driver = (Driver) driverClass.getConstructor().newInstance();
            try (var connection = driver.connect("jdbc:sqlite:" + directory.resolve("packaged.db"), new Properties());
                    var statement = connection.createStatement(); var result = statement.executeQuery("SELECT sqlite_version()")) {
                assertTrue(result.next()); assertEquals("3.54.1", result.getString(1));
            }
        }
    }
}
