# R8 rules for the release build (T55). R8 runs in full mode (the AGP 8 default) with
# proguard-android-optimize.txt. Keep this list short: every library we use ships its own consumer rules,
# and each rule here says why it is needed.
#
# - MapLibre Native: its AAR's consumer rules keep the JNI surface (NativeMapView, the style/layer/source
#   classes the C++ side calls back into, geojson). Nothing to add for normal use.
# - SQLDelight (android-driver) and our generated TrackDb code: no reflection; the generated Kotlin calls the
#   driver directly. Nothing to add.
# - Compose and AndroidX: consumer rules in their AARs. Nothing to add.
# - Our own code: no reflection except the one below; the JSON we serve is built by hand (MiniJson), not by
#   a reflective serializer, so renaming our classes is safe.

# MapController.nativeFullyLoaded reads MapView's private `nativeMapView` field and calls
# `isFullyLoaded()` on it by name (the NativeMap interface is package-private). Without these two rules R8
# renames the field (it became `g` in the first T55 build) and the lookup falls back to MapLibre's per-frame
# `fully` flag (runCatching, no crash). Keeping them makes the idle signal the same in debug and release.
-keepclassmembers class org.maplibre.android.maps.MapView {
    org.maplibre.android.maps.NativeMap nativeMapView;
}
-keepclassmembers class org.maplibre.android.maps.NativeMapView {
    boolean isFullyLoaded();
}
