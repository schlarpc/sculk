package sculk.core.control;

import java.util.Map;

/**
 * One event log entry held in memory.
 *
 * @param id     position in the log, increasing by one per entry
 * @param fields the entry as written to the event log (frame hex may be cut short there)
 * @param json   {@code fields} serialised once, as one JSON line
 * @param frame  for packets, the whole frame; otherwise null
 */
public record StoredEvent(long id, Map<String, Object> fields, String json, byte[] frame) {}
