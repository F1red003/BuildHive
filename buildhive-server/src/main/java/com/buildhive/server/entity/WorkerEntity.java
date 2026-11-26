package com.buildhive.server.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "workers")
public class WorkerEntity {

    @Id
    @Column(name = "id", length = 100)
    private String id;

    @Column(name = "worker_name", nullable = false)
    private String workerName;

    @Column(name = "hostname", nullable = false)
    private String hostname;

    @Column(name = "last_heartbeat", nullable = false)
    private Instant lastHeartbeat;

    @Column(name = "status", nullable = false, length = 50)
    private String status = "ACTIVE";

    @Column(name = "registered_at", nullable = false, updatable = false)
    private Instant registeredAt;

    public WorkerEntity() {
    }

    public WorkerEntity(String id, String workerName, String hostname) {
        this.id = id;
        this.workerName = workerName;
        this.hostname = hostname;
        this.lastHeartbeat = Instant.now();
        this.status = "ACTIVE";
        this.registeredAt = Instant.now();
    }

    @PrePersist
    protected void onCreate() {
        if (this.registeredAt == null) {
            this.registeredAt = Instant.now();
        }
        if (this.lastHeartbeat == null) {
            this.lastHeartbeat = Instant.now();
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getWorkerName() {
        return workerName;
    }

    public void setWorkerName(String workerName) {
        this.workerName = workerName;
    }

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public Instant getLastHeartbeat() {
        return lastHeartbeat;
    }

    public void setLastHeartbeat(Instant lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(Instant registeredAt) {
        this.registeredAt = registeredAt;
    }
}
