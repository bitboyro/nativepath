package ro.bitboy.nativepath;

import ro.bitboy.nativepath.internal.ForeignBridge;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A Rust panic inside a native call must come back as an error result, not kill the JVM. */
class NativePanicTest {

    @Test
    void panicIsContainedAndLibraryStaysUsable() {
        for (int i = 0; i < 3; i++) {
            assertEquals(-1, ForeignBridge.testPanic());
        }
        try (NativePathHttpClient client = NativePathHttpClient.create()) {
            assertNotNull(client);
        }
    }
}
