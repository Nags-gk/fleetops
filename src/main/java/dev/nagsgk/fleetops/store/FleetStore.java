package dev.nagsgk.fleetops.store;

import dev.nagsgk.fleetops.model.Heartbeat;
import dev.nagsgk.fleetops.model.Incident;
import dev.nagsgk.fleetops.model.RobotState;

import java.util.List;
import java.util.Optional;

/** Persistence port. DynamoDB in production, in-memory in unit tests. */
public interface FleetStore {
    Optional<RobotState> getState(String robotId);

    /** Write only if this heartbeat's seq is newer than the stored one (idempotent, out-of-order safe). */
    boolean putStateIfNewer(RobotState state);

    void appendTelemetry(Heartbeat hb);

    List<Heartbeat> telemetry(String robotId, long sinceMillis, int limit);

    Optional<Incident> openIncident(String robotId, String kind);

    Optional<Incident> getIncident(String id);

    void putIncident(Incident incident);

    List<RobotState> listStates();

    List<Incident> listIncidents(int limit);
}
