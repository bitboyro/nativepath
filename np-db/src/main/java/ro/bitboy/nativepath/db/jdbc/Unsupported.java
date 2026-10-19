package ro.bitboy.nativepath.db.jdbc;

import java.sql.SQLFeatureNotSupportedException;

public final class Unsupported {

    public static SQLFeatureNotSupportedException feature(String what) {
        return new SQLFeatureNotSupportedException(what + " is not supported by the NativePath driver yet");
    }

    private Unsupported() {}
}
