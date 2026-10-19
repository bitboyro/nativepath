//! Baseline: plain hyper answering "hello" inline with the system allocator (no zeroing on free).
//! Compare with `hyper_direct` to see what wiping every freed block costs.
//!
//!     cargo run --release -p np-http-server --example hyper_direct_sysalloc -- <port>

#[path = "common/hyper_direct.rs"]
mod common;

fn main() {
    common::run();
}
