# Keep gateway classes intact — private APK, no obfuscation needed
-keep class pl.surfiq.smsgateway.** { *; }
-keep class com.google.gson.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
