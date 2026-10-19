# `np-memory` (Rust)

The memory layer shared by every protocol.

- **Zeroizing global allocator.** Every freed native block is zeroed, including the buffers of
  hyper, rustls and reqwest.
- **`SecretBuffer` and secure wipe** for values that must not be left behind.
- **Process memory scanner** (Linux: `/proc/self/maps` and `process_vm_readv`; macOS: Mach VM) used by
  the project's residue tests to look for planted values in the whole process. It has a self-test:
  plant a value, find it, wipe it, find nothing.

Cost: wiping freed memory is about 1 µs per request. Limits and what it cannot do (stack and register
copies, kernel buffers, swap, core dumps): [security](../security.md).
