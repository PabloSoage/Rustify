# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keep class com.varuna.rustify.bridge.NativeEngine { *; }
-keep class com.varuna.rustify.bridge.** { *; }
-keepclassmembers class com.varuna.rustify.bridge.** { *; }

# YoutubeDL-android and FFmpeg
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.ffmpeg.** { *; }
-keepclassmembers class com.yausername.youtubedl_android.** { *; }
-keepclassmembers class com.yausername.ffmpeg.** { *; }

# Jackson (Used by YoutubeDL-android)
-keep class com.fasterxml.jackson.** { *; }
-keepclassmembers class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.databind.**
-dontwarn java.beans.**
-dontwarn org.w3c.dom.bootstrap.**

# Apache Commons (Used by YoutubeDL-android / FFmpeg)
-keep class org.apache.commons.compress.** { *; }
-keepclassmembers class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**

-keep class org.apache.commons.io.** { *; }
-keepclassmembers class org.apache.commons.io.** { *; }
-dontwarn org.apache.commons.io.**

# Media3 has its own consumer rules
# -keep class androidx.media3.** { *; }