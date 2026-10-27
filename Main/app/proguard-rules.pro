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

# ---- Math Engine (MEP) JNI bridge ----
# Keep JNI bridge class and all native method signatures for R8 / ProGuard.
# Without these, shrinking would strip/renamed the native methods and cause
# java.lang.UnsatisfiedLinkError at MathEngineBridge.nativeCreate().
-keep class com.mathengine.MathEngineBridge { *; }
-keep class com.mathengine.MathEngineBridge$EvalResult { *; }
-keep class com.mathengine.MathEngineKt { *; }
