package com.dan.receiptshooter.service;

import com.dan.receiptshooter.dto.ReceiptApi;
import com.dan.receiptshooter.exception.InvalidUploadException;
import com.dan.receiptshooter.exception.UploadTooLargeException;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

@Component
class UploadValidator {
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private static final byte[] PNG_END = {0, 0, 0, 0, 0x49, 0x45, 0x4e, 0x44, (byte) 0xae, 0x42, 0x60, (byte) 0x82};
    private static final Instant EARLIEST_CAPTURE = Instant.parse("1900-01-01T00:00:00Z");

    private final long maxPhotoBytes;
    private final long maxTotalBytes;
    private final int maxPhotos;
    private final long maxPixels;

    UploadValidator(@Value("${receipt.upload.max-photo-bytes}") long maxPhotoBytes,
                    @Value("${receipt.upload.max-total-bytes}") long maxTotalBytes,
                    @Value("${receipt.upload.max-photos}") int maxPhotos,
                    @Value("${receipt.upload.max-pixels}") long maxPixels,
                    @Value("${spring.servlet.multipart.max-request-size}") long maxRequestBytes) {
        if (maxPhotoBytes < 1 || maxPhotoBytes > 256L * 1024 * 1024 ||
                maxTotalBytes < maxPhotoBytes || maxTotalBytes > 1024L * 1024 * 1024 ||
                maxPhotos < 1 || maxPhotos > 100 || maxPixels < 1 || maxPixels > 200_000_000 ||
                maxRequestBytes < maxTotalBytes + 1024 * 1024) {
            throw new IllegalStateException("Invalid receipt upload limits.");
        }
        this.maxPhotoBytes = maxPhotoBytes;
        this.maxTotalBytes = maxTotalBytes;
        this.maxPhotos = maxPhotos;
        this.maxPixels = maxPixels;
    }

    List<ValidatedPhoto> validate(ReceiptApi.UploadMetadata metadata, MultiValueMap<String, MultipartFile> files) {
        validateTime(metadata.capturedAt());
        if (metadata.photos().size() > maxPhotos) throw new UploadTooLargeException("Too many photos in this receipt.");

        Set<UUID> ids = new HashSet<>();
        Set<Integer> sequences = new HashSet<>();
        Set<String> expectedParts = new HashSet<>();
        for (var photo : metadata.photos()) {
            if (photo == null || photo.id() == null || photo.sequence() <= 0 ||
                    !ids.add(photo.id()) || !sequences.add(photo.sequence())) {
                throw new InvalidUploadException("Photo IDs and positive sequence numbers must be unique.");
            }
            if (photo.capturedAt() != null) validateTime(photo.capturedAt());
            expectedParts.add("photo-" + photo.id());
        }

        for (var entry : files.entrySet()) {
            if (!entry.getKey().equals("metadata") && !expectedParts.contains(entry.getKey())) {
                throw new InvalidUploadException("An unexpected photograph part was supplied.");
            }
            if (entry.getValue().size() != 1) throw new InvalidUploadException("Each multipart part must occur once.");
        }

        List<ValidatedPhoto> result = new ArrayList<>();
        long totalBytes = 0;
        for (var photo : metadata.photos()) {
            var parts = files.get("photo-" + photo.id());
            if (parts == null || parts.size() != 1) throw new InvalidUploadException("A photograph part is missing.");
            MultipartFile file = parts.getFirst();
            if (file.getSize() > maxPhotoBytes) throw new UploadTooLargeException("A photo exceeds the photo size limit.");
            byte[] bytes;
            try (InputStream input = file.getInputStream()) {
                bytes = input.readNBytes(Math.toIntExact(maxPhotoBytes + 1));
            } catch (IOException error) {
                throw new InvalidUploadException("A photograph could not be read.");
            }
            if (bytes.length == 0) throw new InvalidUploadException("Photographs must not be empty.");
            if (bytes.length > maxPhotoBytes) throw new UploadTooLargeException("A photo exceeds the photo size limit.");
            totalBytes += bytes.length;
            if (totalBytes > maxTotalBytes) throw new UploadTooLargeException("The receipt exceeds the total photo size limit.");
            if (photo.size() != null && (photo.size() < 1 || photo.size() != bytes.length)) {
                throw new InvalidUploadException("The declared photo size does not match its contents.");
            }

            String contentType = detectAndValidateImage(bytes);
            checkClaimedContentType(photo.contentType(), contentType);
            checkClaimedContentType(file.getContentType(), contentType);
            result.add(new ValidatedPhoto(photo.id(), photo.sequence(),
                    photo.capturedAt() == null ? null : photo.capturedAt().truncatedTo(ChronoUnit.MICROS),
                    safeFilename(file.getOriginalFilename()), contentType, bytes.length, sha256(bytes), file));
        }
        result.sort((left, right) -> Integer.compare(left.sequence(), right.sequence()));
        return List.copyOf(result);
    }

    private void validateTime(Instant value) {
        if (value == null || value.isBefore(EARLIEST_CAPTURE) || value.isAfter(Instant.now().plus(1, ChronoUnit.DAYS))) {
            throw new InvalidUploadException("Capture timestamps must be valid and not more than one day in the future.");
        }
    }

    private String detectAndValidateImage(byte[] bytes) {
        boolean jpeg = bytes.length >= 4 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
        boolean png = bytes.length >= PNG_SIGNATURE.length + PNG_END.length &&
                Arrays.equals(Arrays.copyOf(bytes, PNG_SIGNATURE.length), PNG_SIGNATURE) &&
                Arrays.equals(Arrays.copyOfRange(bytes, bytes.length - PNG_END.length, bytes.length), PNG_END);
        if (!jpeg && !png) throw new InvalidUploadException("Only valid JPEG and PNG images are supported; HEIC/HEIF is not supported yet.");
        if (jpeg) {
            boolean endMarker = false;
            for (int i = 2; i < bytes.length - 1; i++) {
                if ((bytes[i] & 0xff) == 0xff && (bytes[i + 1] & 0xff) == 0xd9) {
                    endMarker = true;
                    break;
                }
            }
            if (!endMarker) throw new InvalidUploadException("The JPEG image is incomplete.");
        }

        var readers = ImageIO.getImageReadersByFormatName(jpeg ? "JPEG" : "PNG");
        if (!readers.hasNext()) throw new InvalidUploadException("This image format is not supported.");
        ImageReader reader = readers.next();
        boolean[] warning = {false};
        reader.addIIOReadWarningListener((source, message) -> warning[0] = true);
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            reader.setInput(input, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width <= 0 || height <= 0 || width > 32768 || height > 32768 || (long) width * height > maxPixels) {
                throw new UploadTooLargeException("The image dimensions exceed the allowed limit.");
            }
            ImageReadParam params = reader.getDefaultReadParam();
            int subsampling = Math.max(1, Math.max(width, height) / 2048);
            params.setSourceSubsampling(subsampling, subsampling, 0, 0);
            BufferedImage image = reader.read(0, params);
            if (image == null || warning[0]) throw new InvalidUploadException("The photograph is damaged or incomplete.");
        } catch (IOException | IllegalArgumentException error) {
            throw new InvalidUploadException("The photograph is damaged or incomplete.");
        } finally {
            reader.dispose();
        }
        return jpeg ? "image/jpeg" : "image/png";
    }

    private static void checkClaimedContentType(String supplied, String detected) {
        if (supplied == null || supplied.isBlank()) return;
        try {
            MediaType parsed = MediaType.parseMediaType(supplied);
            String base = (parsed.getType() + "/" + parsed.getSubtype()).toLowerCase(Locale.ROOT);
            if (!base.equals(detected) && !base.equals("application/octet-stream")) {
                throw new InvalidUploadException("The declared content type does not match the image.");
            }
        } catch (IllegalArgumentException error) {
            throw new InvalidUploadException("The supplied content type is invalid.");
        }
    }

    private static String safeFilename(String supplied) {
        if (supplied == null || supplied.isBlank()) return null;
        String normalized = supplied.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (name.isBlank() || name.equals(".") || name.equals("..")) return null;
        if (name.length() > 255 || name.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidUploadException("The supplied filename is invalid.");
        }
        return name;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }
}
