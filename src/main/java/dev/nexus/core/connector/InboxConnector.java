package dev.nexus.core.connector;

import java.util.Map;
import java.util.Set;

/** Add a platform adapter as a bean; the Inbox itself has no platform-specific branches. */
public interface InboxConnector {
    String id();
    String source();
    Set<String> eventTypes();
    Map<String,Object> health();
}
