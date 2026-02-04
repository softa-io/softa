package io.softa.framework.web.response;

import io.softa.framework.base.enums.ResponseCode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * API response body:
 *    {
 *      "code": 200,
 *      "message": "Success",
 *      "data": [{...}]
 *    }
 * The code represents the business status code of the response.
 * The message is a brief description of the response.
 * The data is the business result of the API.
 */
@Data
@NoArgsConstructor
@Schema(name = "API Response Body")
public class ApiResponse<T> {

    @Schema(description = "Status Code")
    private Integer code;

    @Schema(description = "Common Message")
    private String message;

    @Schema(description = "Result Data")
    private T data;

    public ApiResponse(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * Directly return success, using `ApiResponse.success()`.
     *
     * @return ApiResponse<T>
     */
    public static <T> ApiResponse<T> success() {
        Integer code = ResponseCode.SUCCESS.getCode();
        String message = ResponseCode.SUCCESS.getMessage();
        return new ApiResponse<>(code, message, null);
    }

    /**
     * Return success with data, using `ApiResponse.success(data)`.
     *
     * @param data data of the response.
     * @return ApiResponse<T>
     * @param <T> The type of the data in the response.
     */
    public static <T> ApiResponse<T> success(T data) {
        Integer code = ResponseCode.SUCCESS.getCode();
        String message = ResponseCode.SUCCESS.getMessage();
        return new ApiResponse<>(code, message, data);
    }

    /**
     * Check if the response is successful.
     */
    public boolean isSuccess() {
        return ResponseCode.SUCCESS.getCode().equals(code);
    }

    /**
     * Return redirect response to trigger client-side redirection.
     *
     * @param url redirect url
     * @return redirect response
     */
    public static ApiResponse<String> redirect(String url) {
        Integer code = ResponseCode.REDIRECT.getCode();
        String message = ResponseCode.REDIRECT.getMessage();
        return new ApiResponse<>(code, message, url);
    }

    public static <T>  ApiResponse<T> error(ResponseCode code) {
        return new ApiResponse<>(code.getCode(), code.getMessage(), null);
    }

    public static <T>  ApiResponse<T> error(String message) {
        Integer code = ResponseCode.ERROR.getCode();
        return new ApiResponse<>(code, message, null);
    }


    public static <T>  ApiResponse<T> error(ResponseCode code, String message) {
        return new ApiResponse<>(code.getCode(), message, null);
    }
}
