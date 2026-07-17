package com.codex.sqltuner.tuning.inputimage;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

@Component
public class InputImageValidator {
    public static final int MAX_IMAGES = 3;
    public static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    public static final int MAX_TOTAL_BYTES = 5 * 1024 * 1024;

    public List<TaskInputImage> validate(List<PlanImageRequest> requests) {
        List<TaskInputImage> images = new ArrayList<TaskInputImage>();
        if (requests == null || requests.isEmpty()) {
            return images;
        }
        if (requests.size() > MAX_IMAGES) {
            throw new IllegalArgumentException("planImages 最多上传 " + MAX_IMAGES + " 张图片");
        }
        int totalBytes = 0;
        for (int i = 0; i < requests.size(); i++) {
            PlanImageRequest request = requests.get(i);
            if (request == null || !hasText(request.getDataUrl())) {
                throw new IllegalArgumentException("planImages[" + i + "] 缺少 dataUrl");
            }
            ParsedDataUrl parsed = parseDataUrl(request.getDataUrl().trim(), i);
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(parsed.base64);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("planImages[" + i + "] 不是合法 base64 图片");
            }
            if (decoded.length == 0 || decoded.length > MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("planImages[" + i + "] 解码后超过 2MiB 或为空");
            }
            totalBytes += decoded.length;
            if (totalBytes > MAX_TOTAL_BYTES) {
                throw new IllegalArgumentException("planImages 总大小超过 5MiB");
            }
            if (!matchesMagicBytes(parsed.mediaType, decoded)) {
                throw new IllegalArgumentException("planImages[" + i + "] MIME 类型与图片 magic bytes 不匹配");
            }
            images.add(new TaskInputImage(null, i, safeName(request.getName()), parsed.mediaType, decoded, sha256(decoded)));
        }
        return images;
    }

    private ParsedDataUrl parseDataUrl(String dataUrl, int index) {
        int comma = dataUrl.indexOf(',');
        if (comma < 0) {
            throw new IllegalArgumentException("planImages[" + index + "] 必须是 data URL");
        }
        String header = dataUrl.substring(0, comma).toLowerCase(Locale.ROOT);
        if (!header.startsWith("data:") || !header.endsWith(";base64")) {
            throw new IllegalArgumentException("planImages[" + index + "] 必须是 base64 data URL");
        }
        String mediaType = header.substring("data:".length(), header.length() - ";base64".length());
        if ("image/jpg".equals(mediaType)) {
            mediaType = "image/jpeg";
        }
        if (!"image/png".equals(mediaType) && !"image/jpeg".equals(mediaType) && !"image/webp".equals(mediaType)) {
            throw new IllegalArgumentException("planImages[" + index + "] 只支持 PNG/JPEG/WebP");
        }
        String base64 = dataUrl.substring(comma + 1);
        if (!hasText(base64) || base64.indexOf('\n') >= 0 || base64.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("planImages[" + index + "] base64 内容非法");
        }
        return new ParsedDataUrl(mediaType, base64);
    }

    private boolean matchesMagicBytes(String mediaType, byte[] data) {
        if ("image/png".equals(mediaType)) {
            return data.length >= 8
                    && (data[0] & 0xff) == 0x89 && data[1] == 0x50 && data[2] == 0x4e && data[3] == 0x47
                    && data[4] == 0x0d && data[5] == 0x0a && data[6] == 0x1a && data[7] == 0x0a;
        }
        if ("image/jpeg".equals(mediaType)) {
            return data.length >= 3 && (data[0] & 0xff) == 0xff && (data[1] & 0xff) == 0xd8 && (data[2] & 0xff) == 0xff;
        }
        if ("image/webp".equals(mediaType)) {
            return data.length >= 12
                    && data[0] == 0x52 && data[1] == 0x49 && data[2] == 0x46 && data[3] == 0x46
                    && data[8] == 0x57 && data[9] == 0x45 && data[10] == 0x42 && data[11] == 0x50;
        }
        return false;
    }

    private String safeName(String value) {
        if (!hasText(value)) {
            return null;
        }
        String normalized = value.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() > 255 ? normalized.substring(0, 255) : normalized;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String sha256(byte[] data) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b & 0xff));
            }
            return builder.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static final class ParsedDataUrl {
        private final String mediaType;
        private final String base64;

        private ParsedDataUrl(String mediaType, String base64) {
            this.mediaType = mediaType;
            this.base64 = base64;
        }
    }
}
