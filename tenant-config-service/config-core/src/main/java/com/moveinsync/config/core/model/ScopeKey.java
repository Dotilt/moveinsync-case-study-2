package com.moveinsync.config.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

public final class ScopeKey {
    private final ScopeLevel level;
    private final String clientId;
    private final String officeId;
    private final String shiftId;

    @JsonCreator
    public ScopeKey(
            @JsonProperty("level") ScopeLevel level,
            @JsonProperty("clientId") String clientId,
            @JsonProperty("officeId") String officeId,
            @JsonProperty("shiftId") String shiftId) {
        this.level = Objects.requireNonNull(level, "level cannot be null");
        this.clientId = clientId;
        this.officeId = officeId;
        this.shiftId = shiftId;
    }

    public static ScopeKey global() {
        return new ScopeKey(ScopeLevel.GLOBAL, null, null, null);
    }

    public static ScopeKey client(String clientId) {
        return new ScopeKey(ScopeLevel.CLIENT, Objects.requireNonNull(clientId, "clientId cannot be null"), null, null);
    }

    public static ScopeKey office(String clientId, String officeId) {
        return new ScopeKey(ScopeLevel.OFFICE,
                Objects.requireNonNull(clientId, "clientId cannot be null"),
                Objects.requireNonNull(officeId, "officeId cannot be null"),
                null);
    }

    public static ScopeKey shift(String clientId, String officeId, String shiftId) {
        return new ScopeKey(ScopeLevel.SHIFT,
                Objects.requireNonNull(clientId, "clientId cannot be null"),
                Objects.requireNonNull(officeId, "officeId cannot be null"),
                Objects.requireNonNull(shiftId, "shiftId cannot be null"));
    }

    public ScopeLevel getLevel() {
        return level;
    }

    public String getClientId() {
        return clientId;
    }

    public String getOfficeId() {
        return officeId;
    }

    public String getShiftId() {
        return shiftId;
    }

    public String toPath() {
        switch (level) {
            case GLOBAL:
                return "GLOBAL";
            case CLIENT:
                return "CLIENT:" + clientId;
            case OFFICE:
                return "CLIENT:" + clientId + "/OFFICE:" + officeId;
            case SHIFT:
                return "CLIENT:" + clientId + "/OFFICE:" + officeId + "/SHIFT:" + shiftId;
            default:
                return "UNKNOWN";
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ScopeKey scopeKey = (ScopeKey) o;
        return level == scopeKey.level &&
                Objects.equals(clientId, scopeKey.clientId) &&
                Objects.equals(officeId, scopeKey.officeId) &&
                Objects.equals(shiftId, scopeKey.shiftId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(level, clientId, officeId, shiftId);
    }

    @Override
    public String toString() {
        return toPath();
    }
}
