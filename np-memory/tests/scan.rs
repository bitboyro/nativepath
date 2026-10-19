use np_memory::alloc::secure_wipe;
use np_memory::scan::count_matches;

const MASK: u8 = 0x5A;

/// A value that exists in memory only while the test holds it: built at run time from its masked
/// form, so no literal copy sits in the binary.
fn plant(masked: &[u8]) -> Vec<u8> {
    masked.iter().map(|b| b ^ MASK).collect()
}

#[test]
fn finds_a_live_value_and_nothing_after_wipe() {
    let masked: Vec<u8> = b"NATIVEPATH-SCAN-SELFTEST-7c41e09b"
        .iter()
        .map(|b| b ^ MASK)
        .collect();
    let before = count_matches(&masked, MASK);
    if before == u64::MAX {
        // Supported on macOS and Linux: there, "unsupported" is a failure, not a skip.
        assert!(
            !cfg!(any(target_os = "macos", target_os = "linux")),
            "memory scan reported unsupported on a platform where it must work"
        );
        eprintln!("memory scan unsupported on this platform; skipping");
        return;
    }
    assert_eq!(before, 0, "value must not exist before it is planted");

    let mut value = plant(&masked);
    let found = count_matches(&masked, MASK);
    assert!(found >= 1, "planted value not found by the scan ({found})");

    secure_wipe(value.as_mut_ptr(), value.len());
    std::hint::black_box(&value);
    assert_eq!(count_matches(&masked, MASK), 0, "value still present after wipe");
}
