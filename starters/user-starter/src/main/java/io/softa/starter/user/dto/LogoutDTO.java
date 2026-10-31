package io.softa.starter.user.dto;

import lombok.Data;

/**
 * Optional body of {@code /UserAccount/logout}. The APP sends the push token of the device it is
 * signing out of, so that device stops receiving this person's notifications.
 */
@Data
public class LogoutDTO {

    private String deviceToken;
}
