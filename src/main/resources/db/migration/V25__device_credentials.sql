-- 设备 MQTT 凭据台账：只保存一次性密钥的 SHA-256 指纹，不保存明文。
-- CORE 不写 Broker 配置文件：部署侧 Mosquitto 配置是只读绑定，且 CORE 容器没有该路径。
-- 管理员按签发/轮换时一次性返回的密钥在主机执行 mosquitto_passwd，并追加本表推导的 ACL。
-- previous_* 表示轮换宽限期内仍然有效的旧凭据；宽限期结束后只保留当前指纹。
CREATE TABLE device_credentials (
    device_id varchar(40) PRIMARY KEY REFERENCES devices(device_id),
    username varchar(60) NOT NULL UNIQUE,
    revision integer NOT NULL DEFAULT 1 CHECK(revision >= 1),
    secret_fingerprint char(64) NOT NULL CHECK(secret_fingerprint ~ '^[0-9a-f]{64}$'),
    previous_fingerprint char(64) CHECK(previous_fingerprint ~ '^[0-9a-f]{64}$'),
    previous_expires_at timestamptz,
    issued_at timestamptz NOT NULL DEFAULT now(),
    rotated_at timestamptz,
    revoked_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(64) NOT NULL,
    CHECK((previous_fingerprint IS NULL)=(previous_expires_at IS NULL))
);

-- 凭据事件只记录身份、动作与修订号，不含密钥或指纹。
CREATE TABLE device_credential_events (
    sequence bigserial PRIMARY KEY,
    device_id varchar(40) NOT NULL,
    revision integer NOT NULL,
    event varchar(24) NOT NULL CHECK(event IN ('issued','reissued','rotated','revoked')),
    actor varchar(64) NOT NULL,
    detail varchar(80) NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX device_credential_events_device ON device_credential_events(device_id, created_at DESC);
