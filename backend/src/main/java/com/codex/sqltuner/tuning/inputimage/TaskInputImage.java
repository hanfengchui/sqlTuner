package com.codex.sqltuner.tuning.inputimage;

import java.time.LocalDateTime;

public class TaskInputImage {
    private Long id;
    private Long taskId;
    private int imageOrder;
    private String fileName;
    private String mediaType;
    private int byteSize;
    private String sha256;
    private byte[] imageData;
    private LocalDateTime createdAt;

    public TaskInputImage() {
    }

    public TaskInputImage(Long taskId, int imageOrder, String fileName, String mediaType, byte[] imageData, String sha256) {
        this.taskId = taskId;
        this.imageOrder = imageOrder;
        this.fileName = fileName;
        this.mediaType = mediaType;
        this.imageData = imageData;
        this.byteSize = imageData == null ? 0 : imageData.length;
        this.sha256 = sha256;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public int getImageOrder() {
        return imageOrder;
    }

    public void setImageOrder(int imageOrder) {
        this.imageOrder = imageOrder;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getMediaType() {
        return mediaType;
    }

    public void setMediaType(String mediaType) {
        this.mediaType = mediaType;
    }

    public int getByteSize() {
        return byteSize;
    }

    public void setByteSize(int byteSize) {
        this.byteSize = byteSize;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public byte[] getImageData() {
        return imageData;
    }

    public void setImageData(byte[] imageData) {
        this.imageData = imageData;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String toDataUrl() {
        return "data:" + mediaType + ";base64," + java.util.Base64.getEncoder().encodeToString(imageData);
    }
}
