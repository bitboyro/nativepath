package ro.bitboy.nativepath;

import ro.bitboy.nativepath.internal.ForeignBridge;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Reads a generic password from the macOS keychain (the default search list, normally the login
 * keychain). The Security framework hands the password to Rust, which copies it into a wiped
 * buffer and zeroes the framework's copy before releasing it; Java only ever sees the native
 * pointer.
 *
 * <p>Create an item with {@code security add-generic-password -s <service> -a <account> -w}
 * (prompts for the value, so it does not end up in shell history). The first read from a new
 * program may show a macOS access dialog; {@code -T /path/to/java} or Keychain Access grants it
 * in advance. A locked keychain or a refused dialog is reported as an {@link IOException}.
 *
 * <p>macOS only. Linux (libsecret) and Windows (Credential Manager) throw
 * {@link UnsupportedOperationException}.
 */
public final class KeychainSecretSource implements SecretSource {

    private static final int ERR_NOT_FOUND = -25300;
    private static final int ERR_INTERACTION_NOT_ALLOWED = -25308;
    private static final int ERR_AUTH_FAILED = -25293;
    private static final int ERR_USER_CANCELED = -128;
    private static final int UNSUPPORTED = Integer.MIN_VALUE;

    private final String service;
    private final String account;

    public KeychainSecretSource(String service, String account) {
        if (service == null || service.isEmpty() || account == null || account.isEmpty()) {
            throw new IllegalArgumentException("keychain service and account are required");
        }
        this.service = service;
        this.account = account;
    }

    @Override
    public NativeSecret load() throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac")) {
            throw unsupported();
        }
        try (Arena arena = Arena.ofConfined()) {
            byte[] s = service.getBytes(StandardCharsets.UTF_8);
            byte[] a = account.getBytes(StandardCharsets.UTF_8);
            MemorySegment cService = arena.allocate(Math.max(s.length, 1));
            MemorySegment cAccount = arena.allocate(Math.max(a.length, 1));
            MemorySegment.copy(MemorySegment.ofArray(s), 0, cService, 0, s.length);
            MemorySegment.copy(MemorySegment.ofArray(a), 0, cAccount, 0, a.length);
            MemorySegment outLen = arena.allocate(ValueLayout.JAVA_LONG);
            MemorySegment outCap = arena.allocate(ValueLayout.JAVA_LONG);
            MemorySegment outStatus = arena.allocate(ValueLayout.JAVA_INT);

            MemorySegment ptr = ForeignBridge.keychainRead(cService, s.length, cAccount, a.length, outLen, outCap, outStatus);
            if (ptr.equals(MemorySegment.NULL)) {
                int status = outStatus.get(ValueLayout.JAVA_INT, 0);
                if (status == UNSUPPORTED) throw unsupported();
                throw new IOException(describe(status));
            }
            return new NativeSecret(ptr.address(), outLen.get(ValueLayout.JAVA_LONG, 0),
                    outCap.get(ValueLayout.JAVA_LONG, 0), false);
        }
    }

    private String describe(int status) {
        String what = switch (status) {
            case ERR_NOT_FOUND -> "item not found";
            case ERR_INTERACTION_NOT_ALLOWED -> "keychain is locked or access needs a confirmation that cannot be shown";
            case ERR_AUTH_FAILED -> "keychain authorization failed";
            case ERR_USER_CANCELED -> "access was refused in the keychain dialog";
            default -> "OSStatus " + status;
        };
        return "keychain read failed for " + this + ": " + what;
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("keychain secrets are supported on macOS only; on Linux (libsecret) "
                + "and Windows use SecretSource.file(...) with a mounted secret, or a vault source");
    }

    @Override
    public String toString() {
        return "keychain(service=" + service + ", account=" + account + ")";
    }
}
