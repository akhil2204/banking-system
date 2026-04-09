package com.banking.notificationservice.entity;

public enum NotificationChannel {
    /*
     * IN_APP: stored in DB and retrieved via API. Implemented now.
     * EMAIL: requires SMTP / SendGrid integration. Deferred.
     * SMS:   requires Twilio / similar. Deferred.
     *
     * The channel field exists now so callers can already send the right
     * intent. When EMAIL is implemented, the service processes those rows
     * without any API contract changes.
     */
    IN_APP,
    EMAIL,
    SMS
}
