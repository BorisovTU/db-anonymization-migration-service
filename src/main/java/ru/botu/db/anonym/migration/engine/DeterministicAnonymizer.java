package ru.botu.db.anonym.migration.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.botu.db.anonym.migration.config.AnonymizationProperties;
import ru.botu.db.anonym.migration.config.AnonymizationStrategy;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Компонент вычисления детерминированных масок на базе ThreadLocal Mac.
 */
@Component
public class DeterministicAnonymizer {

    private static final Logger log = LoggerFactory.getLogger(DeterministicAnonymizer.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final AnonymizationProperties properties;
    private final ThreadLocal<Mac> threadLocalMac;

    public DeterministicAnonymizer(AnonymizationProperties properties) {
        this.properties = properties;
        byte[] keyBytes = properties.getSalt().getBytes(StandardCharsets.UTF_8);
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, HMAC_ALGORITHM);
        this.threadLocalMac = ThreadLocal.withInitial(() -> {
            try {
                Mac mac = Mac.getInstance(HMAC_ALGORITHM);
                mac.init(keySpec);
                return mac;
            } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
                throw new IllegalStateException("Failed to initialize ThreadLocal Mac", ex);
            }
        });
    }

    /**
     * Диспетчер применения стратегий.
     */
    public Object applyStrategy(Object sourceValue, AnonymizationStrategy strategy, String contextId) {
        if (sourceValue == null) {
            log.debug("[{}] Value is null for strategy {}. Preserving null.", contextId, strategy);
            return null;
        }
        return switch (strategy) {
            case AS_IS -> sourceValue;
            case FULL_NAME -> anonymizeFullName(sourceValue.toString(), contextId);
            case PHONE -> anonymizePhone(sourceValue.toString(), contextId);
            case EMAIL -> anonymizeEmail(sourceValue.toString(), contextId);
            case FILE_PATH -> anonymizeFilePath(sourceValue.toString(), contextId);
            case UUID_FK -> {
                UUID uuid = (sourceValue instanceof UUID u) ? u : UUID.fromString(sourceValue.toString());
                yield anonymizeUuid(uuid, contextId);
            }
        };
    }

    public UUID anonymizeUuid(UUID sourceUuid, String contextId) {
        if (sourceUuid == null) {
            return null;
        }
        byte[] hmac = calculateHmac(sourceUuid.toString().getBytes(StandardCharsets.UTF_8), contextId);
        long mostSigBits = 0L;
        long leastSigBits = 0L;
        for (int i = 0; i < 8; i++) {
            mostSigBits = (mostSigBits << 8) | (hmac[i] & 0xFF);
        }
        for (int i = 8; i < 16; i++) {
            leastSigBits = (leastSigBits << 8) | (hmac[i] & 0xFF);
        }
        UUID mapped = new UUID(mostSigBits, leastSigBits);
        log.debug("[{}] UUID mapped deterministically: {} -> {}", contextId, sourceUuid, mapped);
        return mapped;
    }

    public String anonymizeFullName(String sourceName, String contextId) {
        if (sourceName == null || sourceName.isBlank()) {
            return sourceName;
        }
        byte[] hmac = calculateHmac(sourceName.getBytes(StandardCharsets.UTF_8), contextId);
        String hex = HexFormat.of().formatHex(hmac);
        String masked = "Anonymized_User_" + hex.substring(0, 12);
        log.debug("[{}] Full name anonymized: prefix={}", contextId, hex.substring(0, 8));
        return masked;
    }

    public String anonymizeEmail(String sourceEmail, String contextId) {
        if (sourceEmail == null || sourceEmail.isBlank()) {
            return sourceEmail;
        }
        byte[] hmac = calculateHmac(sourceEmail.toLowerCase().trim().getBytes(StandardCharsets.UTF_8), contextId);
        String hex = HexFormat.of().formatHex(hmac);
        String masked = "masked_" + hex.substring(0, 16) + "@" + properties.getEmailDomain();
        log.debug("[{}] Email anonymized with target domain {}", contextId, properties.getEmailDomain());
        return masked;
    }

    public String anonymizePhone(String sourcePhone, String contextId) {
        if (sourcePhone == null || sourcePhone.isBlank()) {
            return sourcePhone;
        }
        byte[] hmac = calculateHmac(sourcePhone.getBytes(StandardCharsets.UTF_8), contextId);
        StringBuilder builder = new StringBuilder("+79");
        for (int i = 0; i < 9; i++) {
            builder.append(Math.abs(hmac[i] % 10));
        }
        String masked = builder.toString();
        log.debug("[{}] Phone masked: format=+79XXXXXXXXX", contextId);
        return masked;
    }

    public String anonymizeFilePath(String sourcePath, String contextId) {
        if (sourcePath == null || sourcePath.isBlank()) {
            return sourcePath;
        }
        int lastSlashIndex = sourcePath.lastIndexOf('/');
        String directory = (lastSlashIndex >= 0) ? sourcePath.substring(0, lastSlashIndex + 1) : "";
        String filename = (lastSlashIndex >= 0) ? sourcePath.substring(lastSlashIndex + 1) : sourcePath;
        int extIndex = filename.lastIndexOf('.');
        String extension = (extIndex >= 0) ? filename.substring(extIndex) : "";
        byte[] hmac = calculateHmac(filename.getBytes(StandardCharsets.UTF_8), contextId);
        String masked = directory + "file_" + HexFormat.of().formatHex(hmac).substring(0, 16) + extension;
        log.debug("[{}] File path masked: dir={}, ext={}", contextId, directory, extension);
        return masked;
    }

    private byte[] calculateHmac(byte[] data, String contextId) {
        long start = System.nanoTime();
        try {
            Mac mac = threadLocalMac.get();
            byte[] result = mac.doFinal(data);
            long duration = System.nanoTime() - start;
            log.debug("[{}] Fast HMAC computed in {} ns", contextId, duration);
            return result;
        } catch (Exception ex) {
            log.error("[{}] Error during HMAC computation: {}", contextId, ex.getMessage(), ex);
            throw new IllegalStateException("HMAC computation failure", ex);
        }
    }
}
