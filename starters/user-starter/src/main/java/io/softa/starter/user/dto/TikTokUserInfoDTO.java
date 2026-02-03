package io.softa.starter.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * TikTok User Info DTO
 */
@Data
public class TikTokUserInfoDTO {

    @JsonProperty("open_id")
    private String openId;

    @JsonProperty("union_id")
    private String unionId;

    @JsonProperty("avatar_url")
    private String avatarUrl;

    @JsonProperty("avatar_url_100")
    private String avatarUrl100;

    @JsonProperty("avatar_large_url")
    private String avatarLargeUrl;

    @JsonProperty("display_name")
    private String displayName;

    @JsonProperty("bio_description")
    private String bioDescription;

    @JsonProperty("profile_deep_link")
    private String profileDeepLink;

    @JsonProperty("is_verified")
    private Boolean isVerified;

    @JsonProperty("follower_count")
    private Integer followerCount;

    @JsonProperty("following_count")
    private Integer followingCount;

    @JsonProperty("likes_count")
    private Integer likesCount;

    @JsonProperty("video_count")
    private Integer videoCount;
}