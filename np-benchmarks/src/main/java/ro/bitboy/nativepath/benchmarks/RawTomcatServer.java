package ro.bitboy.nativepath.benchmarks;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Embedded Tomcat with one servlet and Tomcat's defaults (NIO connector, 200 threads): the standard
 * Java server with no Spring on top. Same routes as the other servers, except that {@code POST /json}
 * echoes the body instead of parsing it.
 *
 *   java ... RawTomcatServer &lt;port&gt;
 */
public class RawTomcatServer {
    private static final byte[] HELLO = "hello".getBytes(StandardCharsets.US_ASCII);

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 18093;
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(System.getProperty("java.io.tmpdir") + "/np-raw-tomcat-" + port);
        Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setPort(port);
        connector.setProperty("address", System.getenv().getOrDefault("BENCH_BIND", "127.0.0.1"));
        tomcat.setConnector(connector);
        Context ctx = tomcat.addContext("", null);
        Tomcat.addServlet(ctx, "bench", new Bench());
        ctx.addServletMappingDecoded("/", "bench");
        tomcat.start();
        System.out.println("READY " + port);
        tomcat.getServer().await();
    }

    private static final class Bench extends HttpServlet {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
            String path = req.getRequestURI();
            byte[] body;
            String type = "text/plain";
            if (path.equals("/payload")) {
                String size = req.getParameter("size");
                body = new byte[size == null ? 0 : Integer.parseInt(size)];
                Arrays.fill(body, (byte) 'x');
                type = "application/octet-stream";
            } else if (path.equals("/echo") || path.equals("/json")) {
                body = req.getInputStream().readAllBytes();
                type = path.equals("/json") ? "application/json" : "application/octet-stream";
            } else if (path.equals("/__stats")) {
                body = BenchStats.json(null).getBytes(StandardCharsets.US_ASCII);
                type = "application/json";
            } else {
                body = HELLO;
            }
            res.setStatus(200);
            res.setContentType(type);
            res.setContentLength(body.length);
            res.getOutputStream().write(body);
        }
    }
}
