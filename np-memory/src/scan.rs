//! Test support: count occurrences of a known value in this process's own memory.
//!
//! The pattern is passed XOR-masked and compared on the fly, so the plaintext value is never
//! assembled in the scanner's memory. macOS (Mach VM calls on the current task) and Linux
//! (`/proc/self/maps` and `process_vm_readv` on the own process); elsewhere it reports unsupported.
//! Each region is copied into a scratch buffer first, so an unreadable page fails the copy instead
//! of crashing the process.

use std::sync::Mutex;

static HITS: Mutex<Vec<u64>> = Mutex::new(Vec::new());

const CHUNK: usize = 1 << 20;

/// Counts masked matches in `buf` (copied from address `base`) and records the first addresses.
/// Matches do not overlap.
fn count_in(buf: &[u8], base: u64, masked: &[u8], mask: u8) -> u64 {
    let m = masked.len();
    let first = masked[0];
    let mut total = 0;
    let mut i = 0;
    while i + m <= buf.len() {
        if buf[i] ^ mask == first && (1..m).all(|j| buf[i + j] ^ mask == masked[j]) {
            total += 1;
            if let Ok(mut h) = HITS.lock() {
                if h.len() < 16 {
                    h.push(base + i as u64);
                }
            }
            i += m;
        } else {
            i += 1;
        }
    }
    total
}

/// A scratch buffer from mmap, so it can be excluded from the scan by address; wiped on drop
/// because it may hold copies of scanned memory.
struct Scratch {
    ptr: *mut libc::c_void,
}

impl Scratch {
    fn new() -> Option<Self> {
        let ptr = unsafe {
            libc::mmap(
                std::ptr::null_mut(),
                CHUNK,
                libc::PROT_READ | libc::PROT_WRITE,
                libc::MAP_PRIVATE | libc::MAP_ANON,
                -1,
                0,
            )
        };
        (ptr != libc::MAP_FAILED).then_some(Self { ptr })
    }

    fn start(&self) -> u64 {
        self.ptr as u64
    }

    fn end(&self) -> u64 {
        self.ptr as u64 + CHUNK as u64
    }

    fn buf(&self, len: usize) -> &[u8] {
        unsafe { std::slice::from_raw_parts(self.ptr as *const u8, len.min(CHUNK)) }
    }
}

impl Drop for Scratch {
    fn drop(&mut self) {
        unsafe {
            std::ptr::write_bytes(self.ptr as *mut u8, 0, CHUNK);
            libc::munmap(self.ptr, CHUNK);
        }
    }
}

/// Scans `[start, end)` chunk by chunk with `read(pos, len) -> bytes copied into the scratch`.
fn scan_region(
    start: u64,
    end: u64,
    scratch: &Scratch,
    masked: &[u8],
    mask: u8,
    mut read: impl FnMut(u64, usize) -> Option<usize>,
) -> u64 {
    let m = masked.len();
    let mut total = 0;
    let mut pos = start;
    while pos < end {
        if pos < scratch.end() && pos + CHUNK as u64 > scratch.start() {
            pos = scratch.end();
            continue;
        }
        let n = ((end - pos) as usize).min(CHUNK);
        let got = match read(pos, n) {
            Some(g) if g >= m => g,
            _ => {
                pos += n as u64;
                continue;
            }
        };
        total += count_in(scratch.buf(got), pos, masked, mask);
        // Overlap so a match spanning two chunks is found exactly once.
        pos += if got > m { (got - (m - 1)) as u64 } else { got as u64 };
    }
    total
}

/// Addresses of the first matches found by the last scan.
pub fn last_hits() -> Vec<u64> {
    HITS.lock().map(|h| h.clone()).unwrap_or_default()
}

#[cfg(target_os = "macos")]
pub fn count_matches(masked: &[u8], mask: u8) -> u64 {
    use std::os::raw::{c_int, c_uint};

    extern "C" {
        static mach_task_self_: c_uint;
        fn mach_vm_region(
            task: c_uint,
            address: *mut u64,
            size: *mut u64,
            flavor: c_int,
            info: *mut c_int,
            count: *mut c_uint,
            object_name: *mut c_uint,
        ) -> c_int;
        fn mach_vm_read_overwrite(task: c_uint, address: u64, size: u64, data: u64, out_size: *mut u64) -> c_int;
    }
    const VM_REGION_BASIC_INFO_64: c_int = 9;
    const VM_PROT_READ: c_int = 1;

    if masked.is_empty() {
        return 0;
    }
    HITS.lock().map(|mut h| h.clear()).ok();
    let Some(scratch) = Scratch::new() else {
        return u64::MAX;
    };
    let task = unsafe { mach_task_self_ };
    let mut total = 0u64;
    let mut address: u64 = 0;
    loop {
        let (mut size, mut count, mut object) = (0u64, 9u32, 0u32);
        let mut info = [0i32; 9];
        let kr = unsafe {
            mach_vm_region(
                task,
                &mut address,
                &mut size,
                VM_REGION_BASIC_INFO_64,
                info.as_mut_ptr(),
                &mut count,
                &mut object,
            )
        };
        if kr != 0 {
            break;
        }
        let (start, end) = (address, address + size);
        address = end;
        if info[0] & VM_PROT_READ == 0 {
            continue;
        }
        total += scan_region(start, end, &scratch, masked, mask, |pos, n| {
            let mut got = 0u64;
            let kr = unsafe { mach_vm_read_overwrite(task, pos, n as u64, scratch.start(), &mut got) };
            (kr == 0).then_some(got as usize)
        });
    }
    total
}

#[cfg(target_os = "linux")]
pub fn count_matches(masked: &[u8], mask: u8) -> u64 {
    if masked.is_empty() {
        return 0;
    }
    HITS.lock().map(|mut h| h.clear()).ok();
    let Ok(maps) = std::fs::read_to_string("/proc/self/maps") else {
        return u64::MAX;
    };
    let Some(scratch) = Scratch::new() else {
        return u64::MAX;
    };
    let pid = unsafe { libc::getpid() };
    let mut total = 0u64;
    let mut readable_any = false;
    for line in maps.lines() {
        // "start-end perms offset dev inode [path]"
        let mut fields = line.split_whitespace();
        let (Some(range), Some(perms)) = (fields.next(), fields.next()) else {
            continue;
        };
        if !perms.starts_with('r') || line.ends_with("[vvar]") || line.ends_with("[vsyscall]") {
            continue;
        }
        let Some((a, b)) = range.split_once('-') else {
            continue;
        };
        let (Ok(start), Ok(end)) = (u64::from_str_radix(a, 16), u64::from_str_radix(b, 16)) else {
            continue;
        };
        total += scan_region(start, end, &scratch, masked, mask, |pos, n| {
            let local = libc::iovec {
                iov_base: scratch.ptr,
                iov_len: n,
            };
            let remote = libc::iovec {
                iov_base: pos as *mut libc::c_void,
                iov_len: n,
            };
            // Copies from our own address space; an unreadable page returns -1 instead of faulting.
            let got = unsafe { libc::process_vm_readv(pid, &local, 1, &remote, 1, 0) };
            if got > 0 {
                readable_any = true;
                Some(got as usize)
            } else {
                None
            }
        });
    }
    if readable_any {
        total
    } else {
        u64::MAX // process_vm_readv refused everything (e.g. a sandbox): unsupported here
    }
}

#[cfg(not(any(target_os = "macos", target_os = "linux")))]
pub fn count_matches(_masked: &[u8], _mask: u8) -> u64 {
    u64::MAX // unsupported on this platform
}
