# debug variant：不混淆，保留行号（可调试）
-keepattributes SourceFile,LineNumberTable
-keep class com.ratel.api.** { *; }
-keep class com.ratel.runtime.** { *; }
-keep class org.lsposed.hiddenapibypass.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn org.lsposed.hiddenapibypass.**
