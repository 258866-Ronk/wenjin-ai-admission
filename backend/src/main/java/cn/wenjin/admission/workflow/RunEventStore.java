package cn.wenjin.admission.workflow;

import cn.wenjin.admission.common.Jsons;
import cn.wenjin.admission.config.AppProperties;
import cn.wenjin.admission.domain.Domain.RunEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Component
public class RunEventStore {
    private static final Logger log = LoggerFactory.getLogger(RunEventStore.class);
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<RunEvent>> events = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<RunEvent>>> subscribers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> sequences = new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;
    private final JdbcClient jdbc;
    private final int retention;

    public RunEventStore(StringRedisTemplate redis, JdbcClient jdbc, AppProperties properties) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.retention = properties.workflow().eventRetention();
    }

    public RunEvent publish(String runId, String event, Map<String, Object> data) {
        long id = sequences.computeIfAbsent(runId, ignored -> new AtomicLong(lastEventId(runId))).incrementAndGet();
        RunEvent value = new RunEvent(id, event, Instant.now(), Map.copyOf(data));
        persist(runId, value);
        CopyOnWriteArrayList<RunEvent> list = events.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>());
        list.add(value);
        while (list.size() > retention) list.removeFirst();
        subscribers.getOrDefault(runId, new CopyOnWriteArrayList<>()).forEach(listener -> listener.accept(value));
        publishRedis(runId, value);
        return value;
    }

    public boolean enqueue(String runId, String conversationId) {
        try {
            redis.opsForStream().add(StreamRecords.mapBacked(Map.of("runId", runId, "conversationId", conversationId))
                    .withStreamKey("admission:jobs"));
            return true;
        } catch (Exception exception) {
            log.info("Redis queue unavailable; in-process worker remains active");
            return false;
        }
    }

    public List<RunEvent> replay(String runId, long afterId) {
        try {
            return jdbc.sql("select event_id,event_name,created_at,event_data_json from advisor_run_events where run_id=:runId and event_id>:afterId order by event_id")
                    .param("runId", runId).param("afterId", afterId)
                    .query((rs, rowNum) -> new RunEvent(rs.getLong(1), rs.getString(2),
                            rs.getTimestamp(3).toInstant(), objectMap(rs.getString(4))))
                    .list();
        } catch (Exception exception) {
            log.warn("Persistent SSE replay unavailable for run {}; using local buffer", runId);
            return events.getOrDefault(runId, new CopyOnWriteArrayList<>()).stream()
                    .filter(e -> e.id() > afterId).toList();
        }
    }

    public AutoCloseable subscribe(String runId, Consumer<RunEvent> listener) {
        subscribers.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>()).add(listener);
        return () -> subscribers.getOrDefault(runId, new CopyOnWriteArrayList<>()).remove(listener);
    }

    private void publishRedis(String runId, RunEvent event) {
        try {
            Map<String, String> data = new LinkedHashMap<>();
            data.put("eventId", String.valueOf(event.id()));
            data.put("event", event.event());
            data.put("timestamp", event.timestamp().toString());
            data.put("data", cn.wenjin.admission.common.Jsons.write(event.data()));
            redis.opsForStream().add(StreamRecords.mapBacked(data).withStreamKey("admission:run-events:" + runId));
        } catch (Exception ignored) {
            // The in-memory event log is the documented local fallback.
        }
    }

    private long lastEventId(String runId) {
        try {
            Long value = jdbc.sql("select coalesce(max(event_id),0) from advisor_run_events where run_id=:runId")
                    .param("runId", runId).query(Long.class).single();
            return value == null ? 0 : value;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void persist(String runId, RunEvent event) {
        try {
            jdbc.sql("insert into advisor_run_events(run_id,event_id,event_name,event_data_json,created_at) values (:runId,:eventId,:eventName,:data,:createdAt)")
                    .param("runId", runId).param("eventId", event.id()).param("eventName", event.event())
                    .param("data", Jsons.write(event.data())).param("createdAt", java.sql.Timestamp.from(event.timestamp())).update();
            long cutoff = event.id() - retention;
            if (cutoff > 0) {
                jdbc.sql("delete from advisor_run_events where run_id=:runId and event_id<=:cutoff")
                        .param("runId", runId).param("cutoff", cutoff).update();
            }
        } catch (Exception exception) {
            log.warn("Could not persist SSE event {} for run {}", event.event(), runId);
        }
    }

    private static Map<String, Object> objectMap(String json) {
        Map<?, ?> raw = Jsons.read(json, Map.class);
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
