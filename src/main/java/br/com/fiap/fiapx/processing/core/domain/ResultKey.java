package br.com.fiap.fiapx.processing.core.domain;

import java.util.UUID;
import java.util.regex.Pattern;

/** The producer token in an object key can differ from the current recovery lease token. */
public record ResultKey(UUID owner, UUID job, UUID producer) {
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern KEY = Pattern.compile("results/(" + UUID_PATTERN + ")/(" + UUID_PATTERN + ")/(" + UUID_PATTERN + ")/frames\\.zip");
    public static ResultKey parse(String key) {
        var match = KEY.matcher(key);
        if (!match.matches()) throw new IllegalArgumentException("Invalid result reference");
        return new ResultKey(UUID.fromString(match.group(1)), UUID.fromString(match.group(2)), UUID.fromString(match.group(3)));
    }
    public String objectKey() { return "results/" + owner + "/" + job + "/" + producer + "/frames.zip"; }
}
