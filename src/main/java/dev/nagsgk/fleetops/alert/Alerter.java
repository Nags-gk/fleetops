package dev.nagsgk.fleetops.alert;

import dev.nagsgk.fleetops.model.Incident;

/** Paging port: SNS in production (email/SMS/chat to on-call), recording fake in tests. */
public interface Alerter {
    void page(Incident incident);
}
