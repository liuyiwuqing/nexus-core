-- 设备遥测的接入点识别与信道宽度：BSSID 只用于区分同名 AP，不含口令。
-- 旧样本和不上报这些字段的固件保持 NULL。
ALTER TABLE device_telemetry_samples
    ADD COLUMN wifi_bssid varchar(17) CHECK (wifi_bssid ~ '^([0-9A-F]{2}:){5}[0-9A-F]{2}$'),
    ADD COLUMN wifi_channel_width smallint CHECK (wifi_channel_width IN (20, 40));
