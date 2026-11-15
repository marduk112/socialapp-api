package com.socialapp.notification.application;

import java.time.Instant;

/**
 * When notifications started: events that occurred earlier never create or retract notifications (no backfill of
 * history; Kafka still retains and replays older events to the new consumer groups).
 */
public interface NotificationCutoff {
    Instant since();
}
