# Vinyl — reguły ProGuard (minifikacja wyłączona w v1).
# OkHttp — zachowanie nazw klas (refleksja).
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
