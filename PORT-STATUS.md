# Android integration delivery

This is a source implementation candidate built from the supplied
madeira-android-swapchain-experimental archive. It is not a compiled APK.

Copy the complete contents into the repository root and push to main.
The workflow compiles the Android runtime components before packaging the APK.
The existing iOS build and embedded source payload are preserved.

Read android/README.md for implemented behavior, verification performed,
known gaps and the combined device acceptance run.

Source dependencies are recorded in runtime/android/sources.json.
WineActivity.java is derived from Wine under LGPL-2.1-or-later; its original
copyright and license header remain intact. The Wine license is included in
android/LICENSE-Wine. Runtime components retain their upstream licenses;
the build collects notices and source revision/patch information alongside them.

The new Android runtime code has not passed GitHub Actions or device tests.
A successful CI result would establish compilation and packaging, not game
compatibility. No runtime execution results are fabricated in this delivery.
