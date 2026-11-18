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

    /** 默认阈值下判定；持久化阈值的行为单独用 evaluate(..., thresholds) 覆盖。 */
    static List<OverviewAlerts.Alert> evaluate(long enabled,long online,long pending,
                                               long total,long failed,boolean ready) {
        return OverviewAlerts.evaluate(enabled,online,pending,total,failed,ready,OverviewAlerts.THRESHOLDS);
    }

    @Test void aQuietSnapshotReturnsNoAlertsAtAll() {
        // 一台启用设备在线、没有积压、样本不足、传输可用：空列表而不是"健康"占位。
        assertThat(evaluate(1,1,0,0,0,true)).isEmpty();
    }

    @Test void unavailableTransportIsTheOnlyErrorLevelAlert() {
        var alerts=evaluate(1,1,0,0,0,false);
        assertThat(codes(alerts)).containsExactly("mqtt_connection");
        var alert=alerts.getFirst();
        assertThat(alert.level()).isEqualTo("error");
        assertThat(alert.comparison()).isEqualTo("lt");
        assertThat(alert.unit()).isEqualTo("state");
        assertThat(alert.value()).isZero();
        assertThat(alert.threshold()).isEqualTo(1);
        // 传输告警排在设备告警前面：读的人先看到"根本发不出去"。
        assertThat(codes(evaluate(2,0,0,0,0,false)))
            .containsExactly("mqtt_connection","devices_online");
    }

    @Test void anEmptyFleetIsNotAnOutage() {
        assertThat(evaluate(0,0,0,0,0,true)).isEmpty();
        assertThat(codes(evaluate(3,0,0,0,0,true))).containsExactly("devices_online");
        assertThat(codes(evaluate(3,2,0,0,0,true))).isEmpty();
    }

    @Test void backlogFiresAtTheThresholdNotAboveIt() {
        assertThat(codes(evaluate(1,1,99,0,0,true))).isEmpty();
        assertThat(codes(evaluate(1,1,100,0,0,true))).containsExactly("delivery_backlog");
    }

    @Test void rateNeedsTwentyDeliveriesBeforeThePercentageCounts() {
        // 19 条全失败是 100%，但样本不足，只报"没有在线设备"这类确定的事。
        assertThat(evaluate(1,1,0,19,19,true)).isEmpty();
        assertThat(codes(evaluate(1,1,0,20,20,true))).containsExactly("delivery_failure_rate");
        // 20 条里 2 条失败正好 10%：等于阈值也命中。
        assertThat(codes(evaluate(1,1,0,20,2,true))).containsExactly("delivery_failure_rate");
        assertThat(codes(evaluate(1,1,0,20,1,true))).isEmpty();
    }

    @Test void configuredThresholdsJudgeTheSameSnapshot() {
        // 默认阈值下安静：在线 1 ≥ 1、积压 99 < 100、5 条样本不足 20。
        assertThat(evaluate(3,1,99,5,3,true)).isEmpty();
        // 换成更严的持久化阈值，同一组计数三项都越线。
        var strict=new OverviewAlerts.Thresholds(0.5,5,10,2);
        var alerts=OverviewAlerts.evaluate(3,1,99,5,3,true,strict);
        assertThat(codes(alerts)).containsExactly("devices_online","delivery_backlog","delivery_failure_rate");
        // 告警里的 threshold 必须是这一次读到的值，而不是把常量又抄一遍。
        assertThat(alerts.get(0).threshold()).isEqualTo(2);
        assertThat(alerts.get(1).threshold()).isEqualTo(10);
        assertThat(alerts.get(2).threshold()).isEqualTo(0.5);
    }

    @Test void thresholdsArePublishedForConsumersThatRenderTheirOwnHealthBar() {
        assertThat(OverviewAlerts.THRESHOLDS.failureRate()).isEqualTo(0.1);
        assertThat(OverviewAlerts.THRESHOLDS.failureRateMinSample()).isEqualTo(20);
        assertThat(OverviewAlerts.THRESHOLDS.backlogPending()).isEqualTo(100);
        assertThat(OverviewAlerts.THRESHOLDS.onlineMin()).isEqualTo(1);
    }

    @Test void drillTargetsPointAtFiltersThatActuallyExist() {
        // 积压看等待投递、失败率看失败投递——这两个状态投递页真的能筛。
        assertThat(target(evaluate(1,1,100,0,0,true))).isEqualTo("deliveries:pending");
        assertThat(target(evaluate(1,1,0,20,2,true))).isEqualTo("deliveries:failed");
        assertThat(target(evaluate(2,0,0,0,0,true))).isEqualTo("devices:offline");
        // 传输不可用没有对应的列表筛选：不给目标，而不是给一个假装能查的跳转。
        var transport=evaluate(1,1,0,0,0,false).getFirst();
        assertThat(transport.code()).isEqualTo("mqtt_connection");
        assertThat(transport.target()).isNull();
    }

    static String target(List<OverviewAlerts.Alert> alerts) {
        var target=alerts.getFirst().target();
        assertThat(target).as("every non-transport alert must be actionable").isNotNull();
        return target.tab()+":"+target.status();
    }
}
