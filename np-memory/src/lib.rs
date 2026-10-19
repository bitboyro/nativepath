//! Memory-safety building blocks shared by the NativePath client and server: the zeroizing
//! global allocator, a fast secure wipe, `SecretBuffer`, and a process memory scanner for tests.

pub mod alloc;
pub mod scan;

use alloc::secure_wipe;

/// Zeroes a vector's whole allocation (not just its length) and empties it.
pub fn wipe_vec(v: &mut Vec<u8>) {
    secure_wipe(v.as_mut_ptr(), v.capacity());
    v.clear();
}

/// A secure, zeroizable off-heap buffer.
/// When dropped or explicitly wiped, memory is overwritten with zeroes using
/// volatile writes that the compiler is forbidden from optimizing away.
#[derive(Debug)]
pub struct SecretBuffer {
    data: Vec<u8>,
}

impl Drop for SecretBuffer {
    fn drop(&mut self) {
        wipe_vec(&mut self.data);
    }
}

impl SecretBuffer {
    pub fn new(data: Vec<u8>) -> Self {
        Self { data }
    }

    pub fn from_slice(slice: &[u8]) -> Self {
        Self {
            data: slice.to_vec(),
        }
    }

    pub fn as_slice(&self) -> &[u8] {
        &self.data
    }

    pub fn len(&self) -> usize {
        self.data.len()
    }

    pub fn is_empty(&self) -> bool {
        self.data.is_empty()
    }

    /// Explicitly zero out the contents and release memory.
    pub fn wipe(&mut self) {
        wipe_vec(&mut self.data);
        self.data.clear();
        self.data.shrink_to_fit();
    }

    /// Consumes the buffer and returns a raw pointer and length for FFI.
    /// Caller is responsible for calling `SecretBuffer::free_raw` to zero and free.
    pub fn into_raw_parts(mut self) -> (*mut u8, usize, usize) {
        let ptr = self.data.as_mut_ptr();
        let len = self.data.len();
        let cap = self.data.capacity();
        std::mem::forget(self); // Prevent drop so memory isn't zeroed prematurely
        (ptr, len, cap)
    }

    /// Safely reconstructs, zeroes out, and frees a raw buffer passed to foreign languages.
    ///
    /// # Safety
    /// Pointer must have originated from `into_raw_parts` with the matching capacity.
    pub unsafe fn free_raw(ptr: *mut u8, len: usize, cap: usize) {
        if !ptr.is_null() && cap > 0 {
            let mut vec = Vec::from_raw_parts(ptr, len, cap);
            wipe_vec(&mut vec);
            drop(vec);
        }
    }
}
