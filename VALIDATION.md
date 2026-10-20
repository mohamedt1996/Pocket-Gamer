# Validation performed for this source delivery

Passed locally:
- Python compilation and shell syntax checks for runtime scripts.
- Parsing of all Java files, including the generated Wine Activity.
- Applying the Wine integration patches to files from pinned revision
  256288853b206373cbf65b775143d33bb11035dd.
- Parsing the combined GitHub workflow and comparing all original iOS and
  unrelated jobs with the supplied archive: unchanged.
- Compiling and executing the private shared-memory adapter test on the Linux
  host: create/reopen/resize, 0600 permissions, traversal rejection and unlink.
- Running runtime packaging with clearly synthetic test binaries in a temporary
  directory: manifest generation and rejection of an incorrectly typed PE DLL.
  No synthetic test binaries are in this delivered archive.

Not performed:
- Android Java or NDK compilation.
- Wine/FEX/DXVK/VKD3D native source builds on GitHub Actions.
- APK installation, Windows CPU execution, game rendering, audio or input on a
  physical Android device.

There is no compiled APK or IPA in this archive. A successful source validation
is not a successful Android runtime test. Use android/README.md's combined
acceptance run after the workflow finishes.
