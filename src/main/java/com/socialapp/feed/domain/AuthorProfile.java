package com.socialapp.feed.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "author_profiles")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class AuthorProfile {

    @Id
    @Column(name = "author_id", columnDefinition = "uuid")
    private UUID authorId;

    @Column(name = "avatar_url")
    private String avatarUrl;

    public AuthorProfile(UUID authorId, String avatarUrl) {
        this.authorId = authorId;
        this.avatarUrl = avatarUrl;
    }

    public void updateAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }
}
