package com.freecontent.community.content.interaction.model;

import java.time.Instant;

public record BookmarkListQuery(int limit,String userId,Instant snapshotAt,Instant expiresAt,Instant lastAt,String lastId) { }
