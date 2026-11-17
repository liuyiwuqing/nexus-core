-- 设备遥测的 Wi-Fi 链路信息；旧样本与未上报的设备为 NULL。
ALTER TABLE device_telemetry_samples
    ADD COLUMN wifi_ip varchar(15),
    ADD COLUMN wifi_rssi smallint CHECK(wifi_rssi BETWEEN -130 AND 0);
