package dev.nagsgk.fleetops.store;

import dev.nagsgk.fleetops.model.Heartbeat;
import dev.nagsgk.fleetops.model.Incident;
import dev.nagsgk.fleetops.model.RobotState;
import dev.nagsgk.fleetops.util.Json;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Duration;
import java.util.*;

/**
 * DynamoDB adapter. Three tables:
 * <pre>
 *   RobotState  pk robotId                      latest state + detector stats, conditional on seq
 *   Telemetry   pk robotId, sk "ts#seq"         time series, TTL-expired after 7 days
 *   Incidents   pk id, sparse GSI openKey       openKey = robotId#kind only while OPEN (dedup lookup)
 * </pre>
 */
public final class DynamoFleetStore implements FleetStore {
    public static final String OPEN_INDEX = "open-key-index";
    private static final long TTL_SECONDS = Duration.ofDays(7).toSeconds();

    private final DynamoDbClient ddb;
    private final String stateTable, telemetryTable, incidentTable;

    public DynamoFleetStore(DynamoDbClient ddb, String stateTable, String telemetryTable, String incidentTable) {
        this.ddb = ddb;
        this.stateTable = stateTable;
        this.telemetryTable = telemetryTable;
        this.incidentTable = incidentTable;
    }

    static String sortKey(Heartbeat hb) {
        return "%013d#%012d".formatted(hb.tsMillis(), hb.seq());
    }

    private static AttributeValue s(String v) {
        return AttributeValue.fromS(v);
    }

    private static AttributeValue n(long v) {
        return AttributeValue.fromN(Long.toString(v));
    }

    @Override
    public Optional<RobotState> getState(String robotId) {
        var r = ddb.getItem(GetItemRequest.builder().tableName(stateTable).key(Map.of("robotId", s(robotId)))
                .consistentRead(true).build());
        return r.hasItem() && r.item().containsKey("doc")
                ? Optional.of(Json.read(r.item().get("doc").s(), RobotState.class)) : Optional.empty();
    }

    @Override
    public boolean putStateIfNewer(RobotState st) {
        try {
            ddb.putItem(PutItemRequest.builder().tableName(stateTable)
                    .item(Map.of("robotId", s(st.last().robotId()), "seq", n(st.last().seq()),
                            "ts", n(st.last().tsMillis()), "doc", s(Json.write(st))))
                    .conditionExpression("attribute_not_exists(robotId) OR seq < :seq")
                    .expressionAttributeValues(Map.of(":seq", n(st.last().seq())))
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false; // duplicate delivery or an older heartbeat arriving late
        }
    }

    @Override
    public void appendTelemetry(Heartbeat hb) {
        ddb.putItem(PutItemRequest.builder().tableName(telemetryTable).item(Map.of(
                "robotId", s(hb.robotId()), "sk", s(sortKey(hb)), "doc", s(Json.write(hb)),
                "expiresAt", n(hb.tsMillis() / 1000 + TTL_SECONDS))).build());
    }

    @Override
    public List<Heartbeat> telemetry(String robotId, long sinceMillis, int limit) {
        var r = ddb.query(QueryRequest.builder().tableName(telemetryTable)
                .keyConditionExpression("robotId = :r AND sk >= :s")
                .expressionAttributeValues(Map.of(":r", s(robotId), ":s", s("%013d".formatted(sinceMillis))))
                .limit(limit).build());
        return r.items().stream().map(i -> Json.read(i.get("doc").s(), Heartbeat.class)).toList();
    }

    private Map<String, AttributeValue> incidentItem(Incident i) {
        var m = new HashMap<String, AttributeValue>();
        m.put("id", s(i.id()));
        m.put("lastSeenAt", n(i.lastSeenAt()));
        m.put("doc", s(Json.write(i)));
        if ("OPEN".equals(i.status())) m.put("openKey", s(i.robotId() + "#" + i.kind()));
        return m;
    }

    @Override
    public Optional<Incident> openIncident(String robotId, String kind) {
        var r = ddb.query(QueryRequest.builder().tableName(incidentTable).indexName(OPEN_INDEX)
                .keyConditionExpression("openKey = :k")
                .expressionAttributeValues(Map.of(":k", s(robotId + "#" + kind))).limit(1).build());
        return r.items().stream().findFirst().map(i -> Json.read(i.get("doc").s(), Incident.class));
    }

    @Override
    public Optional<Incident> getIncident(String id) {
        var r = ddb.getItem(GetItemRequest.builder().tableName(incidentTable).key(Map.of("id", s(id))).build());
        return r.hasItem() ? Optional.of(Json.read(r.item().get("doc").s(), Incident.class)) : Optional.empty();
    }

    @Override
    public void putIncident(Incident i) {
        ddb.putItem(PutItemRequest.builder().tableName(incidentTable).item(incidentItem(i)).build());
    }

    @Override
    public List<RobotState> listStates() {
        // Demo-scale fleets: a paginated scan. At warehouse scale this becomes a per-site GSI query.
        var out = new ArrayList<RobotState>();
        for (var page : ddb.scanPaginator(ScanRequest.builder().tableName(stateTable).build())) {
            page.items().forEach(i -> out.add(Json.read(i.get("doc").s(), RobotState.class)));
        }
        return out;
    }

    @Override
    public List<Incident> listIncidents(int limit) {
        var out = new ArrayList<Incident>();
        for (var page : ddb.scanPaginator(ScanRequest.builder().tableName(incidentTable).build())) {
            page.items().forEach(i -> out.add(Json.read(i.get("doc").s(), Incident.class)));
        }
        out.sort(Comparator.comparingLong(Incident::lastSeenAt).reversed());
        return out.subList(0, Math.min(limit, out.size()));
    }
}
