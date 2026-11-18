package dev.nexus.core.web;

import java.util.ArrayList;
import java.util.List;

/**
 * 概览告警口径：阈值是代码里的固定常量，读时按同一快照派生。
 * 不落库、不推送、不写审计——连续轮询里同一条告警重复出现是正常的。
 * value 与 threshold 同一个单位，comparison 说明比较方向，消费方不需要再猜阈值语义。
 */
final class OverviewAlerts {
    static final double FAILURE_RATE = 0.10;
    // 比例要有样本才有意义：1 条失败 1 条就是 100%，不据此告警。
    static final long FAILURE_RATE_MIN_SAMPLE = 20;
    static final long BACKLOG_PENDING = 100;
    static final long ONLINE_MIN = 1;

    /**
     * 告警指向哪里：{@code tab} 是管理台的页签名，{@code status} 是该页可以真正过滤的状态。
     * 只有"能落到一个现存筛选里"的告警才带目标——传输不可用没有对应的列表筛选，
     * 硬给一个跳转等于假装能查。
     */
    record AlertTarget(String tab,String status) {}
    record Alert(String code,String level,double value,double threshold,String comparison,String unit,
                 AlertTarget target) {}
    record Thresholds(double failureRate,long failureRateMinSample,long backlogPending,long onlineMin) {}
    static final Thresholds THRESHOLDS = new Thresholds(FAILURE_RATE,FAILURE_RATE_MIN_SAMPLE,BACKLOG_PENDING,ONLINE_MIN);

    private OverviewAlerts() {}

    /** 返回顺序固定：传输、设备、积压、失败率；没有命中就是空列表，不返回"健康"占位。 */
    static List<Alert> evaluate(long enabled,long onlineEnabled,long pending,
                                long todayDeliveries,long todayFailed,boolean mqttReady) {
        List<Alert> alerts = new ArrayList<>(4);
        if (!mqttReady) alerts.add(new Alert("mqtt_connection","error",0,1,"lt","state",null));
        // 一台设备都没登记时不报"全部离线"：没有设备不是故障。
        if (enabled > 0 && onlineEnabled < ONLINE_MIN)
            alerts.add(new Alert("devices_online","warn",onlineEnabled,ONLINE_MIN,"lt","count",
                new AlertTarget("devices","")));
        if (pending >= BACKLOG_PENDING)
            alerts.add(new Alert("delivery_backlog","warn",pending,BACKLOG_PENDING,"gte","count",
                new AlertTarget("deliveries","pending")));
        if (todayDeliveries >= FAILURE_RATE_MIN_SAMPLE) {
            double rate = (double) todayFailed / todayDeliveries;
            if (rate >= FAILURE_RATE)
                alerts.add(new Alert("delivery_failure_rate","warn",rate,FAILURE_RATE,"gte","ratio",
                    new AlertTarget("deliveries","failed")));
        }
        return alerts;
    }
}
