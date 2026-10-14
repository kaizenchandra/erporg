package com.atlas.modules.corehr.application.model;

import java.time.Instant;

public record Notification(long id, String message, Instant recordedAt) {}
