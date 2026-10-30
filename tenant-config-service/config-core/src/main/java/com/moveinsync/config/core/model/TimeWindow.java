package com.moveinsync.config.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;

public final class TimeWindow {
    private final String start; // HH:mm format
    private final String end;   // HH:mm format

    @JsonCreator
    public TimeWindow(
            @JsonProperty("start") String start,
            @JsonProperty("end") String end) {
        this.start = Objects.requireNonNull(start, "start time cannot be null");
        this.end = Objects.requireNonNull(end, "end time cannot be null");
    }

    public String getStart() {
        return start;
    }

    public String getEnd() {
        return end;
    }

    public LocalTime parseStart() {
        return LocalTime.parse(start);
    }

    public LocalTime parseEnd() {
        return LocalTime.parse(end);
    }

    public boolean isValidWindow() {
        try {
            LocalTime s = parseStart();
            LocalTime e = parseEnd();
            return !s.equals(e); // Start and end must not be identical
        } catch (DateTimeParseException ex) {
            return false;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TimeWindow that = (TimeWindow) o;
        return Objects.equals(start, that.start) && Objects.equals(end, that.end);
    }

    @Override
    public int hashCode() {
        return Objects.hash(start, end);
    }

    @Override
    public String toString() {
        return start + "-" + end;
    }
}
