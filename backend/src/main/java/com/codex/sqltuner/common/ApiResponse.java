package com.codex.sqltuner.common;

public class ApiResponse<T> {
    private boolean success;
    private String message;
    private T data;
    private String code;
    private String requestId;
    private Object details;

    public ApiResponse() {
    }

    public ApiResponse(boolean success, String message, T data) {
        this.success = success;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<T>(true, null, data);
    }

    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<T>(false, message, null);
    }

    public static <T> ApiResponse<T> fail(String code, String message, String requestId, Object details) {
        ApiResponse<T> response = new ApiResponse<T>(false, message, null);
        response.setCode(code);
        response.setRequestId(requestId);
        response.setDetails(details);
        return response;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Object getDetails() {
        return details;
    }

    public void setDetails(Object details) {
        this.details = details;
    }
}
