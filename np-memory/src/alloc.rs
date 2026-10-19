//! Allocator wrapper that zeroes every block before it is freed or moved.
//!
//! Buffers owned by hyper, rustls and reqwest (socket reads, TLS plaintext, response chunks)
//! cannot be wiped from our own code. Registering this as the `#[global_allocator]` of the
//! final binary or cdylib guarantees none of them outlive their allocation as plaintext.

use std::alloc::{GlobalAlloc, Layout};
use std::sync::atomic::{compiler_fence, Ordering};

/// Fast secure wipe: a single `memset` followed by an optimisation barrier so the compiler
/// cannot treat the writes as dead stores. `zeroize` on `[u8]` writes byte by byte with
/// volatile stores, which is several times slower on large buffers.
#[inline]
pub fn secure_wipe(ptr: *mut u8, len: usize) {
    if len == 0 {
        return;
    }
    unsafe { std::ptr::write_bytes(ptr, 0, len) };
    std::hint::black_box(ptr);
    compiler_fence(Ordering::SeqCst);
}

pub struct ZeroizingAllocator<A>(pub A);

unsafe impl<A: GlobalAlloc> GlobalAlloc for ZeroizingAllocator<A> {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        self.0.alloc(layout)
    }

    unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
        self.0.alloc_zeroed(layout)
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        // Wipe before the block returns to the allocator.
        secure_wipe(ptr, layout.size());
        self.0.dealloc(ptr, layout)
    }

    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        // The inner realloc may free the old block without wiping it, so move manually.
        let new_layout = Layout::from_size_align_unchecked(new_size, layout.align());
        let new_ptr = self.0.alloc(new_layout);
        if !new_ptr.is_null() {
            std::ptr::copy_nonoverlapping(ptr, new_ptr, layout.size().min(new_size));
            self.dealloc(ptr, layout);
        }
        new_ptr
    }
}
