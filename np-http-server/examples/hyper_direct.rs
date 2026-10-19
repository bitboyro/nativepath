//! Baseline: plain hyper answering "hello" inline, with the zeroizing allocator the engine uses.
//! Compare with `engine_bench` (same allocator, lane design) to see what the design costs.
//!
//!     cargo run --release -p np-http-server --example hyper_direct -- <port>

use np_memory::alloc::ZeroizingAllocator;

#[global_allocator]
static GLOBAL: ZeroizingAllocator<std::alloc::System> = ZeroizingAllocator(std::alloc::System);

#[path = "common/hyper_direct.rs"]
mod common;

fn main() {
    common::run();
}
