-- 设备列表按设备汇总尚未接收的投递。只索引未完成状态，避免整表扫描。
CREATE INDEX deliveries_waiting_device ON deliveries (device_id) WHERE status IN ('pending','sent');
