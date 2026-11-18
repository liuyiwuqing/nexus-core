package dev.nexus.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 告警判定是纯函数：阈值、比较方向和样本门槛在这里固定，
 * 不需要数据库或 Broker 就能覆盖"MQTT 不可用"这类夹具起不来的分支。
 */
class OverviewAlertsTest {
    static List<String> codes(List<OverviewAlerts.Alert> alerts) {
        return alerts.stream().map(OverviewAlerts.Alert::code).toList();
    }

    @Test void aQuietSnapshotReturnsNoAlertsAtAll() {
        // 一台启用设备在线、没有积压、样本不足、传输可用：空列表而不是"健康"占位。
        assertThat(OverviewAlerts.evaluate(1,1,0,0,0,true)).isEmpty();
    }

    @Test void unavailableTransportIsTheOnlyErrorLevelAlert() {
        var alerts=OverviewAlerts.evaluate(1,1,0,0,0,false);
        assertThat(codes(alerts)).containsExactly("mqtt_connection");
        var alert=alerts.getFirst();
        assertThat(alert.level()).isEqualTo("error");
        assertThat(alert.comparison()).isEqualTo("lt");
        assertThat(alert.unit()).isEqualTo("state");
        assertThat(alert.value()).isZero();
        assertThat(alert.threshold()).isEqualTo(1);
        // 传输告警排在设备告警前面：读的人先看到"根本发不出去"。
        assertThat(codes(OverviewAlerts.evaluate(2,0,0,0,0,false)))
            .containsExactly("mqtt_connection","devices_online");
    }

    @Test void anEmptyFleetIsNotAnOutage() {
        assertThat(OverviewAlerts.evaluate(0,0,0,0,0,true)).isEmpty();
        assertThat(codes(OverviewAlerts.evaluate(3,0,0,0,0,true))).containsExactly("devices_online");
        assertThat(codes(OverviewAlerts.evaluate(3,2,0,0,0,true))).isEmpty();
    }

    @Test void backlogFiresAtTheThresholdNotAboveIt() {
        assertThat(codes(OverviewAlerts.evaluate(1,1,99,0,0,true))).isEmpty();
        assertThat(codes(OverviewAlerts.evaluate(1,1,100,0,0,true))).containsExactly("delivery_backlog");
    }

    @Test void rateNeedsTwentyDeliveriesBeforeThePercentageCounts() {
        // 19 条全失败是 100%，但样本不足，只报"没有在线设备"这类确定的事。
        assertThat(OverviewAlerts.evaluate(1,1,0,19,19,true)).isEmpty();
        assertThat(codes(OverviewAlerts.evaluate(1,1,0,20,20,true))).containsExactly("delivery_failure_rate");
        // 20 条里 2 条失败正好 10%：等于阈值也命中。
        assertThat(codes(OverviewAlerts.evaluate(1,1,0,20,2,true))).containsExactly("delivery_failure_rate");
        assertThat(codes(OverviewAlerts.evaluate(1,1,0,20,1,true))).isEmpty();
    }

    @Test void thresholdsArePublishedForConsumersThatRenderTheirOwnHealthBar() {
        assertThat(OverviewAlerts.THRESHOLDS.failureRate()).isEqualTo(0.1);
        assertThat(OverviewAlerts.THRESHOLDS.failureRateMinSample()).isEqualTo(20);
        assertThat(OverviewAlerts.THRESHOLDS.backlogPending()).isEqualTo(100);
        assertThat(OverviewAlerts.THRESHOLDS.onlineMin()).isEqualTo(1);
    }
}
