# dav4jvm uses reflection to find WebDAV property classes
-keep class at.bitfire.dav4jvm.property.** { *; }
-keep class at.bitfire.dav4jvm.** { *; }

# vcard4android contact row handlers
-keep class at.bitfire.vcard4android.** { *; }


# ez-vcard (used by vcard4android)
-keep class ezvcard.** { *; }
-dontwarn ezvcard.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.internal.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Hilt
-keepclassmembers,allowobfuscation class * {
    @javax.inject.Inject <init>(...);
    @javax.inject.Inject <fields>;
}
