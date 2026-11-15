package com.socialapp.notification.application;

import java.util.List;

/** One page of notifications, newest first; {@code next} is null on the last page. */
public record NotificationPage(List<NotificationView> items, NotificationCursor next) {
}
