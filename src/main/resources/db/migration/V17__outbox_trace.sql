-- W3C traceparent of the request that wrote the event, so its trace continues through Kafka to the consumers.
ALTER TABLE outbox_events ADD COLUMN trace_parent VARCHAR(55);
