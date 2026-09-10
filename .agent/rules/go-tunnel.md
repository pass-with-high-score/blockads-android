# Go Tunnel & Gomobile Interop Rules

## Gomobile Type Constraints
When modifying or exporting functions, structs, or methods in `tunnel/`:
- **Allowed Types**:
  - Signed integers (`int`, `int8`, `int16`, `int32`, `int64`).
  - Unsigned integers: only `byte` / `uint8` is supported across boundaries.
  - Floating point: `float32`, `float64`.
  - `string`, `bool`.
  - Single-dimensional byte slice `[]byte`.
  - Any Go interface whose methods use only gomobile-supported types.
- **Disallowed Types**:
  - `uint` / `uint32` / `uint64` (must be converted or cast to signed ints or `int64`).
  - Slices of non-byte types (e.g., `[]string`, `[]int` - use JSON strings, delimited strings, or sequential getter methods instead).
  - Maps (`map[string]...`) cannot be directly passed across JNI; serialize as JSON string or use iterator interface.

## Performance & Concurrency
- **Zero Allocations in Hot Paths**: DNS packet parsing and Bloom filter lookups run at high frequency. Reuse buffers and avoid allocating objects per DNS query.
- **Thread Safety**: The Go engine handles concurrent DNS and TCP requests across goroutines. Protect shared states with `sync.RWMutex` or atomic operations.
- **Rebuilding the Tunnel**:
  - After modifying `tunnel/*.go`, rebuild the AAR using `./scripts/build_tunnel.sh` or `./gradlew buildGoTunnel`.
  - Output binary is placed at `app/libs/tunnel.aar`.
