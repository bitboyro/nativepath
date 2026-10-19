package ro.bitboy.nativepath;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Where a credential comes from. {@link #load()} reads it straight into native memory, so the
 * value never exists as a Java {@code String} or {@code byte[]}.
 *
 * <p>Every call returns a new {@link NativeSecret} that refuses heap copies. The caller owns it
 * and must close it, ideally right after use:
 *
 * <pre>{@code
 * SecretSource apiKey = SecretSource.keychain("billing-api", "prod");
 * try (NativeSecret key = apiKey.load();
 *      NativePathRequest req = NativePathRequest.get(url).secretHeader("X-Api-Key", key);
 *      NativePathStream s = client.getStream(req)) {
 *     ...
 * }
 * }</pre>
 *
 * A source holds only the location (path, keychain item, vault path), never the value, so it is
 * safe to keep in a field or a Spring bean. Implementations must fail rather than fall back to a
 * heap copy when the backend cannot deliver the value natively, and must keep secret material
 * out of exception messages.
 */
@FunctionalInterface
public interface SecretSource {

    NativeSecret load() throws IOException;

    /** A file such as a mounted Kubernetes or Docker secret (see {@link NativeSecret#fromFile}). */
    static SecretSource file(Path path) {
        return () -> NativeSecret.fromFile(path);
    }

    /** A generic password in the macOS keychain (see {@link KeychainSecretSource}). */
    static SecretSource keychain(String service, String account) {
        return new KeychainSecretSource(service, account);
    }
}
