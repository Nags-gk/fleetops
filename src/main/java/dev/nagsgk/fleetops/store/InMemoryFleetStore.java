package dev.nagsgk.fleetops.store;

import dev.nagsgk.fleetops.model.Heartbeat;
import dev.nagsgk.fleetops.model.Incident;
import dev.nagsgk.fleetops.model.RobotState;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryFleetStore implements FleetStore {
    private final Map<String, RobotState> states = new ConcurrentHashMap<>();
    private final Map<String, NavigableMap<String, Heartbeat>> telemetry = new ConcurrentHashMap<>();
    private final Map<String, Incident> incidents = new ConcurrentHashMap<>();

    @Override
    public Optional<RobotState> getState(String robotId) {
        return Optional.ofNullable(states.get(robotId));
    }

    @Override
    public synchronized boolean putStateIfNewer(RobotState s) {
        var cur = states.get(s.last().robotId());
        if (cur != null && cur.last().seq() >= s.last().seq()) return false;
        states.put(s.last().robotId(), s);
        return true;
    }

    @Override
    public void appendTelemetry(Heartbeat hb) {
        telemetry.computeIfAbsent(hb.robotId(), k -> new TreeMap<>()).put(DynamoFleetStore.sortKey(hb), hb);
    }

    @Override
    public List<Heartbeat> telemetry(String robotId, long sinceMillis, int limit) {
        var m = telemetry.getOrDefault(robotId, new TreeMap<>());
        return m.tailMap("%013d".formatted(sinceMillis), true).values().stream().limit(limit).toList();
    }

    @Override
    public Optional<Incident> openIncident(String robotId, String kind) {
        return incidents.values().stream()
                .filter(i -> i.robotId().equals(robotId) && i.kind().equals(kind) && "OPEN".equals(i.status()))
                .findFirst();
    }

    @Override
    public Optional<Incident> getIncident(String id) {
        return Optional.ofNullable(incidents.get(id));
    }

    @Override
    public void putIncident(Incident i) {
        incidents.put(i.id(), i);
    }

    @Override
    public List<RobotState> listStates() {
        return List.copyOf(states.values());
    }

    @Override
    public List<Incident> listIncidents(int limit) {
        return incidents.values().stream().sorted(Comparator.comparingLong(Incident::lastSeenAt).reversed()).limit(limit).toList();
    }
}
