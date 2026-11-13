package dev.nexus.core.automation;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;

public final class DailySchedule {
    private DailySchedule() {}
    /** Gap times shift forward; overlap times share a local-date deduplication key. */
    public static Optional<String> due(LocalTime time, ZoneId zone, Instant now) {
        var day = now.atZone(zone).toLocalDate();
        var scheduled = day.atTime(time).atZone(zone).toInstant();
        var delay = Duration.between(scheduled, now);
        return !delay.isNegative() && delay.compareTo(Duration.ofHours(2)) <= 0 ? Optional.of(day.toString()) : Optional.empty();
    }
}
