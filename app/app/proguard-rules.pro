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

# ===== NoctaliX (Parte 7) =====
# Codice dell'app conservato intero: niente rischi con Gson, enum salvati per nome,
# Worker, chiamate da Python (Chaquopy) e reflection. R8 riduce comunque le librerie.
-keep class com.wboelens.polarrecorder.** { *; }

# Polar BLE SDK e protobuf-lite: conservati interi (il Bluetooth non si prova sull'emulatore).
-keep class com.polar.** { *; }
-keep class fi.polar.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

# Attributi per generics (Gson TypeToken), annotazioni e numeri di riga negli errori.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
