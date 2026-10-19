package ro.bitboy.nativepath.spring.server;

import ro.bitboy.nativepath.NativePathHttpServer;

/**
 * Lane tuning and the queue limit, shared by the WebFlux and Servlet servers; each value is the
 * {@link NativePathHttpServer.Builder} setting of the same name, and the defaults are the builder's.
 */
public final class EngineSettings {

    private int batch = 1;
    private int spin = 0;
    private boolean workStealing = false;
    private int maxQueued = 0;

    /** See {@link NativePathHttpServer.Builder#batch(int)}. */
    public EngineSettings batch(int batch) {
        this.batch = batch;
        return this;
    }

    /** See {@link NativePathHttpServer.Builder#spin(int)}. */
    public EngineSettings spin(int spin) {
        this.spin = spin;
        return this;
    }

    /** See {@link NativePathHttpServer.Builder#workStealing(boolean)}. */
    public EngineSettings workStealing(boolean on) {
        this.workStealing = on;
        return this;
    }

    /** See {@link NativePathHttpServer.Builder#maxQueued(int)}. */
    public EngineSettings maxQueued(int max) {
        this.maxQueued = max;
        return this;
    }

    public NativePathHttpServer.Builder applyTo(NativePathHttpServer.Builder builder) {
        builder.batch(batch).spin(spin).workStealing(workStealing);
        return maxQueued > 0 ? builder.maxQueued(maxQueued) : builder;
    }
}
