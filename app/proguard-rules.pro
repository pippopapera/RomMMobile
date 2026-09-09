# --- kotlinx.serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.rommmobile.app.**$$serializer { *; }
-keepclassmembers class com.rommmobile.app.** { *** Companion; }
-keepclasseswithmembers class com.rommmobile.app.** { kotlinx.serialization.KSerializer serializer(...); }

# --- Retrofit / OkHttp ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>

# --- commons-compress / xz: reflective access to optional codecs ---
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn org.brotli.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**

# --- zxing ---
-dontwarn com.google.zxing.**

# --- Keep the QR/device pairing data classes readable in stack traces ---
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
