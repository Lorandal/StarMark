# Miuix loads optional OEM APIs by reflection; those APIs live in the platform.
# Keep the Flutter embedding's generated entry point used by native startup.
-keep class io.flutter.plugins.GeneratedPluginRegistrant { *; }
# Xiaomi's supplied native filter resolves these exact JNI symbol names.
-keep class com.xiaomi.milab.filtersdk.CandySDK { *; }
# The streaming codec exports class-qualified JNI names.
-keep class dev.starmark.starmark.StreamingJpeg { *; }
