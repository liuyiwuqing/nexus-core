package dev.nexus.core;

import static org.assertj.core.api.Assertions.assertThat;
import dev.nexus.core.automation.DailySchedule;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class DailyScheduleTest {
    @Test void dueTimeUsesTheRulesTimezoneAndLimitsCatchupToTwoHours() {
        var time = LocalTime.of(8, 0); var zone = ZoneId.of("Asia/Shanghai");
        assertThat(DailySchedule.due(time, zone, Instant.parse("2026-11-10T00:15:00Z"))).hasValue("2026-11-10");
        assertThat(DailySchedule.due(time, zone, Instant.parse("2026-11-09T23:59:00Z"))).isEmpty();
        assertThat(DailySchedule.due(time, zone, Instant.parse("2026-11-10T02:00:01Z"))).isEmpty();
    }
    @Test void daylightSavingGapsShiftForwardAndOverlapKeepsOneDailyKey() {
        var zone = ZoneId.of("America/New_York");
        assertThat(DailySchedule.due(LocalTime.of(2,30), zone, Instant.parse("2026-03-08T07:35:00Z"))).hasValue("2026-03-08");
        assertThat(DailySchedule.due(LocalTime.of(1,30), zone, Instant.parse("2026-11-01T05:40:00Z"))).hasValue("2026-11-01");
        assertThat(DailySchedule.due(LocalTime.of(1,30), zone, Instant.parse("2026-11-01T06:40:00Z"))).hasValue("2026-11-01");
    }
}
