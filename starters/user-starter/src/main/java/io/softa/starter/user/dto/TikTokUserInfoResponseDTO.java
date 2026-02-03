package io.softa.starter.user.dto;

import lombok.Data;

/**
 * The response DTO for fetching user information from the TikTok API
 */
@Data
public class TikTokUserInfoResponseDTO {

    /**
     * TikTok API's data is wrapped in the data field
     */
    private DataWrapper data;

    /**
     * TikTok data wrapper class
     */
    @Data
    public static class DataWrapper {
        /**
         * TikTok's user data is wrapped in the data.user field
         */
        private TikTokUserInfoDTO user;
    }
}