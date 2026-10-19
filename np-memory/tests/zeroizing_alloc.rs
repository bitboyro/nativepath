use np_memory::alloc::ZeroizingAllocator;
use std::alloc::System;

#[global_allocator]
static GLOBAL: ZeroizingAllocator<System> = ZeroizingAllocator(System);

const PATTERN: u8 = 0xA7;
const SIZE: usize = 2048; // small enough to stay in malloc's free lists, where residue persists

fn leftover(ptr: *const u8, len: usize) -> usize {
    // Reads freed memory on purpose to inspect residue; only valid as a test probe.
    (0..len).filter(|&i| unsafe { std::ptr::read_volatile(ptr.add(i)) } == PATTERN).count()
}

// Note: this passes even without the allocator on macOS (libmalloc clears freed blocks),
// so it guards against regressions only. `old_block_is_wiped_on_realloc` is the discriminating test.
#[test]
fn freed_block_is_wiped() {
    let buf = vec![PATTERN; SIZE];
    let ptr = buf.as_ptr();
    drop(buf);
    // Allocator metadata may overwrite a few leading bytes, but the body must be zero.
    assert_eq!(leftover(ptr, SIZE), 0, "plaintext survived free");
}

#[test]
fn old_block_is_wiped_on_realloc() {
    let mut buf = vec![PATTERN; SIZE];
    let old = buf.as_ptr();
    buf.reserve(SIZE * 64); // forces a move to a larger block
    if buf.as_ptr() != old {
        assert_eq!(leftover(old, SIZE), 0, "plaintext survived realloc move");
    }
    assert!(buf.iter().all(|&b| b == PATTERN), "data corrupted by realloc");
}
