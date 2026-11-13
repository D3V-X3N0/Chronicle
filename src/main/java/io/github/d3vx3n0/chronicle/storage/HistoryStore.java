package io.github.d3vx3n0.chronicle.storage;

import io.github.d3vx3n0.chronicle.model.CaptureGroup;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

/** Small fault-injection seam. Every method is called exclusively by the writer thread. */
public interface HistoryStore extends AutoCloseable {
    String engineVersion();
    long previousUnclean() throws SQLException;
    long previousCoverageIncidents() throws SQLException;
    long earliestUncleanStart() throws SQLException;
    long startSession(long startedAt, String runtime) throws SQLException;
    long write(List<CaptureGroup> groups, List<Incident> incidents, boolean clean, long stoppedAt) throws SQLException;
    default io.github.d3vx3n0.chronicle.recovery.RecoveryRepository recovery() { throw new UnsupportedOperationException("Recovery unavailable"); }
    default <T> T recoveryWork(HistoryWriter.RecoveryWork<T> work) throws SQLException { return work.run(recovery()); }
    @Override void close() throws SQLException, IOException;
}
