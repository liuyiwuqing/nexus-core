-- 设备遥测的 Wi-Fi 网络质量：SSID、主信道与最近一次断线。
-- 旧样本和不上报这些字段的固件保持 NULL；SSID 只作网络标识，不含口令。
ALTER TABLE device_telemetry_samples
    ADD COLUMN wifi_ssid varchar(32),
    ADD COLUMN wifi_channel smallint CHECK(wifi_channel BETWEEN 1 AND 196),
    ADD COLUMN wifi_disconnect_reason smallint CHECK(wifi_disconnect_reason BETWEEN 1 AND 255),
    ADD COLUMN wifi_disconnected_at timestamptz;
