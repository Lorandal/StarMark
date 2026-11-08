# mozjpeg

The unmodified `mozjpeg-4.1.5.tar.gz` archive is vendored for reproducible,
offline builds. CMake verifies its SHA-256 before extracting it into the build
directory. No codec is downloaded at build time or at runtime.

- Upstream: https://github.com/mozilla/mozjpeg
- Tag: https://github.com/mozilla/mozjpeg/tree/v4.1.5
- Archive: https://codeload.github.com/mozilla/mozjpeg/tar.gz/refs/tags/v4.1.5
- SHA-256: `9fcbb7171f6ac383f5b391175d6fb3acde5e64c4c4727274eade84ed0998fcc1`
- Licenses: upstream `LICENSE.md`, `README.ijg` and individual source notices
  remain in the archive; distribution notices are in
  `assets/licenses/mozjpeg-LICENSE.txt`.

Android Bitmap.compress requires a complete Bitmap. That would require about
768 MiB just for a 12288 × 16384 RGBA image, before source, decoration and codec
buffers. The NDK bitmap compressor likewise accepts a complete image rather
than incremental rows. The libjpeg-compatible scanline API meets this export's
bounded-memory requirement.

The encoder selects `JCP_FASTEST` **before** `jpeg_set_defaults`. It explicitly
disables scan optimization, trellis, optimized entropy coding and progressive
scans. In this version's `jcinit.c`, the full-image coefficient buffer is only
requested when `num_scans > 1`, `optimize_coding`, `optimize_scans` or
`trellis_quant` is set. All four are false in our encoder. Its pixel buffers
therefore scale with row width, not image height. Quality defaults to 100,
4:4:4 chroma and accurate integer DCT preserve fine coloured decoration.

Only the static libjpeg target is built and linked into `libstarmark_jpeg.so`;
TurboJPEG, PNG tooling, Java wrappers and codec shared libraries are disabled.
ARM builds use NEON; x86 builds use portable C to avoid a separate NASM build
dependency. The JNI shared library uses 16 KiB ELF load alignment on every ABI.

This software is based in part on the work of the Independent JPEG Group.
