# The original IJK playback runtime resolves Java classes and native methods by name.
-keep class tv.danmaku.ijk.media.player.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.fenghuolun.ktv.KtvApplication { *; }
-keep class com.fenghuolun.ktv.MainActivity { *; }
-keep class com.fenghuolun.ktv.BootReceiver { *; }
-dontwarn edu.umd.cs.findbugs.annotations.SuppressFBWarnings
