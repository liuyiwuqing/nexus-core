-- 概览告警阈值：单行表，读时与计数在同一快照里取出，不在进程里缓存。
-- version 用于乐观并发：两个人同时改，后提交的人收到 409 而不是悄悄覆盖。
CREATE TABLE alert_thresholds (
    singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
    failure_rate double precision NOT NULL CHECK(failure_rate >= 0 AND failure_rate <= 1),
    failure_rate_min_sample integer NOT NULL CHECK(failure_rate_min_sample BETWEEN 1 AND 100000),
    backlog_pending integer NOT NULL CHECK(backlog_pending BETWEEN 1 AND 1000000),
    online_min integer NOT NULL CHECK(online_min BETWEEN 1 AND 1000),
    version integer NOT NULL CHECK(version >= 1),
    updated_by varchar(64) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO alert_thresholds(singleton,failure_rate,failure_rate_min_sample,backlog_pending,online_min,version,updated_by)
VALUES(true,0.10,20,100,1,1,'system');

-- 阈值变更审计：一行保存这一次写入后的完整阈值与前后版本，方便回答"什么时候被谁改成了多少"。
CREATE TABLE alert_threshold_audit (
    sequence bigserial PRIMARY KEY,
    actor varchar(64) NOT NULL,
    from_version integer NOT NULL,
    to_version integer NOT NULL,
    failure_rate double precision NOT NULL,
    failure_rate_min_sample integer NOT NULL,
    backlog_pending integer NOT NULL,
    online_min integer NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX alert_threshold_audit_recorded ON alert_threshold_audit(recorded_at DESC, sequence DESC);
