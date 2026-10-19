package ro.bitboy.nativepath.benchmarks;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Plain Netty, no Spring and no reactive layer: the fastest HTTP/1.1 a Java server gets from this
 * stack, to put next to {@code engine} and {@code engine_java}. Same routes as the other servers,
 * except that {@code POST /json} echoes the body instead of parsing it.
 *
 *   java ... RawNettyServer &lt;port&gt; [threads=0 (one per core)]
 */
public class RawNettyServer {
    private static final byte[] HELLO = "hello".getBytes(StandardCharsets.US_ASCII);

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 18092;
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        String bind = System.getenv().getOrDefault("BENCH_BIND", "127.0.0.1");
        NioEventLoopGroup group = new NioEventLoopGroup(threads);
        new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 4096)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(8 << 20), new Handler());
                    }
                }).bind(bind, port).sync();
        System.out.println("READY " + port);
        Thread.currentThread().join();
    }

    private static final class Handler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
            String uri = req.uri();
            byte[] body;
            String type = "text/plain";
            if (uri.startsWith("/payload")) {
                int q = uri.indexOf("size=");
                int size = q < 0 ? 0 : Integer.parseInt(uri.substring(q + 5));
                body = new byte[size];
                Arrays.fill(body, (byte) 'x');
                type = "application/octet-stream";
            } else if (uri.startsWith("/echo") || uri.startsWith("/json")) {
                body = new byte[req.content().readableBytes()];
                req.content().readBytes(body);
                type = uri.startsWith("/json") ? "application/json" : "application/octet-stream";
            } else if (uri.equals("/__stats")) {
                body = BenchStats.json(null).getBytes(StandardCharsets.US_ASCII);
                type = "application/json";
            } else {
                body = HELLO;
            }
            DefaultFullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                    Unpooled.wrappedBuffer(body));
            res.headers().set(HttpHeaderNames.CONTENT_TYPE, type).setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
            boolean keep = HttpUtil.isKeepAlive(req);
            if (keep) {
                res.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
                ctx.writeAndFlush(res);
            } else {
                ctx.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
