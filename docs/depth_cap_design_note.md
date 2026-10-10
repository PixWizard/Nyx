# Design Note: Payload-Aware Depth Cap for Edge-Adaptive Embedding

> **STATUS: PROPOSED DESIGN NOTE ONLY — NOT IMPLEMENTED IN THE CODEBASE**  
> Target: `org.ghost` JPMS Module (`nyx-stegano`)

---

## 1. Concept & Motivation

In the current implementation, enabling Sobel edge-adaptive embedding (`--adaptive`) allocates bit depth dynamically up to `SobelEdgeDetector.MAX_DEPTH = 3` bits per color channel on high-gradient edges ($G \ge T_3 = 112$). While this increases carrier capacity on high-frequency textured carriers (from ~98 KB up to ~160 KB on a 512x512 image), higher bit substitutions ($d = 2$ or $d = 3$) introduce higher mean absolute change (MAC) per channel.

For small payloads (such as a 10 KB payload in a 512x512 carrier) that comfortably fit within depth-1 capacity, the unconstrained adaptive engine incurs an unnecessary PSNR penalty:
- **Constant Depth 1 (Unadaptive)**: ~60.94 dB PSNR
- **Adaptive (Unconstrained $D = 3$)**: ~57 dB PSNR

A **payload-aware depth cap** determines the smallest cap $D \in \{1, 2, 3\}$ during encoding such that the carrier's capped capacity satisfies:
$$\text{Capacity}(D) \ge \text{CiphertextLength} \times 8$$

Selecting $D = 1$ when the payload fits preserves the visual fidelity of depth 1 while keeping the smooth-region skipping property ($d = 0$).

---

## 2. On-Disk Header Storage & Versioning

### Candidate Storage in the 50-Byte Header
The 50-byte envelope header (bytes 0..49) offers two potential integration points:
1. **Header Flags (Byte 6)**: Bits 2 and 3 of the `flags` field (currently reserved/validated as 0) can store $(D - 1) \in \{0, 1, 2\}$ as a 2-bit unsigned integer when Bit 1 (`FLAG_ADAPTIVE`) is set.
2. **Profile ID (Byte 7)**: Introduce dedicated `DepthProfile` IDs (e.g., `0x02` for capped at $D = 2$, `0x03` for capped at $D = 1$).

### Version Bump Requirement
**A format version bump is mandatory (magic `NYX\x02` at bytes 0..3).**

Existing v1 decoders deserialize the header, detect `FLAG_ADAPTIVE`, and compute the active embedding mask assuming `SobelEdgeDetector.MAX_DEPTH = 3`. If a carrier were encoded with cap $D \in \{1, 2\}$, a v1 decoder would read 2 or 3 bits from edge pixels, immediately desynchronizing the bitstream and failing with a generic `AuthenticationFailedException` on AES-GCM verification. A version bump ensures older decoders fail fast with an explicit `UnsupportedFormatException`.

---

## 3. Decoder Mask Derivation & Invariance Under Cap

### The Correctness Invariant
The correctness of edge-adaptive steganography requires that:
$$\text{SobelMask}(\text{Cover}) \equiv \text{SobelMask}(\text{Stego})$$

In Nyx, edge gradients are evaluated strictly on invariant bitplanes:
- `SobelEdgeDetector.MAX_DEPTH = 3`
- `SobelEdgeDetector.INVARIANT_MASK = ~((1 << MAX_DEPTH) - 1) & 0xFF` (evaluates to `0xF8`)

Because $D \le \text{MAX\_DEPTH}$ for all valid caps $D \in \{1, 2, 3\}$, embedding under any cap only modifies bits within the lowest $D \le 3$ positions. The invariant expression `channel & SobelEdgeDetector.INVARIANT_MASK` is bit-identical between the cover image and the stego image.

### Decoder Derivation Steps
1. The decoder reads cap $D$ from the header.
2. The decoder computes Sobel gradient $G$ across all pixels using `SobelEdgeDetector.INVARIANT_MASK` (`0xF8`).
3. For each pixel, the base depth $d_{\text{raw}} \in \{0, 1, 2, 3\}$ is evaluated from the threshold profile ($T_1 = 16, T_2 = 48, T_3 = 112$).
4. The active depth is derived by clamping:
   $$d_{\text{active}}[p] = \min(d_{\text{raw}}[p], D)$$
   - Smooth pixels ($G < T_1$) remain $d_{\text{active}} = 0$ (skipped).
   - Edge pixels ($G \ge T_1$) are capped at $D$.

---

## 4. Effects on Capacity and PSNR

On a 512x512 textured carrier with a 10 KB payload:
- **Constant Depth 1**: ~60.94 dB PSNR, capacity ~98 KB.
- **Adaptive ($D = 1$ Cap)**: ~60.94 dB PSNR, capacity ~95 KB (retaining $d = 0$ on flat regions).
- **Adaptive ($D = 3$ Uncapped)**: ~57 dB PSNR, capacity ~160 KB.

For payloads $\le 95$ KB, cap $D = 1$ avoids the ~4 dB PSNR penalty. For payloads between 95 KB and 142 KB, cap $D = 2$ accommodates the data with intermediate distortion. Full $D = 3$ is reserved for payloads exceeding 142 KB.

---

## 5. Risks and Trade-offs

1. **Steganalytic Footprint**: Embedding at $D = 1$ exclusively on edge pixels creates an asymmetric Pairs-of-Values distribution compared to flat constant-depth-1 embedding.
2. **Carrier Dependency**: On predominantly smooth carriers ($G < 16$), capacity under $D = 1$ is lower than unadaptive constant depth 1 because smooth pixels are skipped entirely.
3. **Compatibility**: Requires version 2 header parsing and maintaining dual-version decoder support.
