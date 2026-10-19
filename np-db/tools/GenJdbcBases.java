import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/**
 * Generates the JDBC base types in {@code ro.bitboy.nativepath.db.jdbc}: every method throws
 * SQLFeatureNotSupportedException, and the driver overrides what it supports.
 *
 * <p>Run from the repository root after a JDK upgrade adds JDBC methods:
 * {@code java np-db/tools/GenJdbcBases.java np-db/src/main/java/ro/bitboy/nativepath/db/jdbc ro.bitboy.nativepath.db.jdbc}
 */
public class GenJdbcBases {
    public static void main(String[] a) throws Exception {
        Path out = Path.of(a[0]);
        String pkg = a[1];
        gen(out, pkg, "BaseConnection", java.sql.Connection.class, null);
        gen(out, pkg, "BaseStatement", java.sql.Statement.class, null);
        gen(out, pkg, "UnsupportedPreparedStatement", java.sql.PreparedStatement.class, java.sql.Statement.class);
        gen(out, pkg, "BaseResultSet", java.sql.ResultSet.class, null);
        gen(out, pkg, "BaseDatabaseMetaData", java.sql.DatabaseMetaData.class, null);
    }

    static String sig(Method m) {
        return m.getName() + Arrays.toString(m.getParameterTypes());
    }

    static void gen(Path out, String pkg, String name, Class<?> iface, Class<?> parentIface) throws Exception {
        Set<String> inherited = new HashSet<>();
        if (parentIface != null) for (Method m : parentIface.getMethods()) inherited.add(sig(m));
        Map<String, Method> methods = new TreeMap<>();
        for (Method m : iface.getMethods()) {
            if (m.isDefault() || Modifier.isStatic(m.getModifiers())) continue;
            if (inherited.contains(sig(m))) continue;
            String key = m.getName() + "(" + Arrays.toString(m.getParameterTypes()) + ")";
            methods.putIfAbsent(key, m);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("/**\n * Generated from {@code ").append(iface.getName()).append("}: every method throws\n")
          .append(" * {@link java.sql.SQLFeatureNotSupportedException}. Implementations override what they support,\n")
          .append(" * so anything else fails loudly instead of silently doing nothing.\n */\n");
        // A sub-interface (PreparedStatement) becomes an interface of default methods, so a class can
        // extend the shared statement implementation and still pick these up.
        boolean asInterface = parentIface != null;
        if (asInterface) {
            sb.append("public interface ").append(name).append(" extends ").append(iface.getName()).append(" {\n");
        } else {
            sb.append("public abstract class ").append(name).append(" implements ").append(iface.getName()).append(" {\n");
        }
        for (Method m : methods.values()) {
            sb.append("\n    @Override\n    ").append(asInterface ? "default " : "public ");
            TypeVariable<Method>[] tps = m.getTypeParameters();
            if (tps.length > 0) {
                StringJoiner j = new StringJoiner(", ", "<", "> ");
                for (TypeVariable<Method> t : tps) j.add(t.getName());
                sb.append(j);
            }
            sb.append(m.getGenericReturnType().getTypeName().replace('$', '.')).append(' ').append(m.getName()).append('(');
            Type[] ps = m.getGenericParameterTypes();
            StringJoiner pj = new StringJoiner(", ");
            for (int i = 0; i < ps.length; i++) {
                String t = ps[i].getTypeName().replace('$', '.');
                if (m.isVarArgs() && i == ps.length - 1) t = t.substring(0, t.length() - 2) + "...";
                pj.add(t + " p" + i);
            }
            sb.append(pj).append(')');
            boolean sqlEx = Arrays.asList(m.getExceptionTypes()).stream().anyMatch(e -> e.isAssignableFrom(java.sql.SQLException.class));
            if (m.getExceptionTypes().length > 0) {
                StringJoiner ej = new StringJoiner(", ", " throws ", "");
                for (Class<?> e : m.getExceptionTypes()) ej.add(e.getName());
                sb.append(ej);
            }
            sb.append(" {\n        throw ");
            if (sqlEx) sb.append("Unsupported.feature(\"").append(iface.getSimpleName()).append('.').append(m.getName()).append("\");\n");
            else sb.append("new UnsupportedOperationException(\"").append(iface.getSimpleName()).append('.').append(m.getName()).append("\");\n");
            sb.append("    }\n");
        }
        sb.append("}\n");
        Files.writeString(out.resolve(name + ".java"), sb.toString());
        System.out.println(name + ": " + methods.size() + " methods");
    }
}
