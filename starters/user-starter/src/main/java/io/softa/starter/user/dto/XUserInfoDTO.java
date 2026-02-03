package io.softa.starter.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * X (Twitter) User Info DTO
 */
@Data
public class XUserInfoDTO {

    private String id;

    private String name;

    private String username;

    @JsonProperty("profile_image_url")
    private String profileImageUrl;

    @JsonProperty("public_metrics")
    private PublicMetrics publicMetrics;

    private String description;

    private String location;

    private Boolean verified;

    @JsonProperty("created_at")
    private String createdAt;

    @Data
    public static class PublicMetrics {
        @JsonProperty("followers_count")
        private Integer followersCount;

        @JsonProperty("following_count")
        private Integer followingCount;

        @JsonProperty("tweet_count")
        private Integer tweetCount;

        @JsonProperty("listed_count")
        private Integer listedCount;

        @JsonProperty("like_count")
        private Integer likeCount;

        @JsonProperty("media_count")
        private Integer mediaCount;
    }
}