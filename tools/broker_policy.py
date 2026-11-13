"""Versioned, device-scoped broker rules. No credentials are read here."""
LEGACY_ACL = """user nexus-core
topic write nexus/v1/devices/+/inbox
topic read nexus/v1/devices/+/acks
topic read nexus/v1/devices/+/state
pattern read nexus/v1/devices/%u/inbox
pattern write nexus/v1/devices/%u/acks
pattern write nexus/v1/devices/%u/state
"""
MANAGEMENT_ACL = """user nexus-core
topic write nexus/v1/devices/+/inbox
topic write nexus/v1/devices/+/config
topic read nexus/v1/devices/+/acks
topic read nexus/v1/devices/+/state
topic read nexus/v1/devices/+/config_acks
topic read nexus/v1/devices/+/telemetry
pattern read nexus/v1/devices/%u/inbox
pattern read nexus/v1/devices/%u/config
pattern write nexus/v1/devices/%u/acks
pattern write nexus/v1/devices/%u/state
pattern write nexus/v1/devices/%u/config_acks
pattern write nexus/v1/devices/%u/telemetry
"""

# Preserve the former policy for recognized upgrades; existing custom ACLs require review.
MANAGEMENT_V1_ACL=MANAGEMENT_ACL
MANAGEMENT_ACL=MANAGEMENT_V1_ACL.replace("topic write nexus/v1/devices/+/config\n", "topic write nexus/v1/devices/+/config\ntopic write nexus/v1/devices/+/ota\n").replace(
    "topic read nexus/v1/devices/+/telemetry\n", "topic read nexus/v1/devices/+/telemetry\ntopic read nexus/v1/devices/+/ota_acks\ntopic read nexus/v1/devices/+/ota_status\n")+"pattern read nexus/v1/devices/%u/ota\npattern write nexus/v1/devices/%u/ota_acks\npattern write nexus/v1/devices/%u/ota_status\n"

def upgrade_acl(text):
    rules = lambda value: [line.strip() for line in value.splitlines() if line.strip() and not line.lstrip().startswith("#")]
    if rules(text) == rules(MANAGEMENT_ACL):
        return text
    if rules(text) not in (rules(LEGACY_ACL),rules(MANAGEMENT_V1_ACL)):
        raise ValueError("custom_acl_requires_manual_merge")
    return MANAGEMENT_ACL

def upgrade_config(text):
    lines = text.splitlines()
    anonymous = [line.split()[1:] for line in lines if line.strip().startswith("allow_anonymous ")]
    if not anonymous or any(value != ["false"] for value in anonymous):
        raise ValueError("broker_authentication_must_remain_enabled")
    for key, minimum in (("message_size_limit", 2048), ("max_packet_size", 4096)):
        found = [i for i, line in enumerate(lines) if line.strip().startswith(key + " ")]
        if len(found) > 1:
            raise ValueError("duplicate_broker_limit_requires_manual_merge")
        if found:
            try:
                current = int(lines[found[0]].split()[1])
            except (IndexError, ValueError):
                raise ValueError("invalid_broker_limit") from None
            if current < minimum:
                lines[found[0]] = f"{key} {minimum}"
        else:
            lines.append(f"{key} {minimum}")
    return "\n".join(lines) + "\n"
