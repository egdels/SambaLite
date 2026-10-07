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
#-keepattributes LineNumberTable,SourceFile
#-renamesourcefileattribute SourceFile

# Dagger 2 (without Hilt) is pure compile-time code generation and needs no keep
# rules. In particular "-keep class * { @javax.inject.* <fields>; }" must not be
# used: a class specification of "*" matches every class and prevented R8 from
# renaming anything, which Google Play flagged as 0 % obfuscation.
-keepattributes RuntimeVisibleAnnotations

# Timber rules
-dontwarn org.jetbrains.annotations.**
-keep class timber.log.** { *; }

-dontwarn com.google.api.client.http.GenericUrl
-dontwarn com.google.api.client.http.HttpHeaders
-dontwarn com.google.api.client.http.HttpRequest
-dontwarn com.google.api.client.http.HttpRequestFactory
-dontwarn com.google.api.client.http.HttpResponse
-dontwarn com.google.api.client.http.HttpTransport
-dontwarn com.google.api.client.http.javanet.NetHttpTransport$Builder
-dontwarn com.google.api.client.http.javanet.NetHttpTransport
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy
-dontwarn javax.annotation.concurrent.ThreadSafe
-dontwarn javax.el.BeanELResolver
-dontwarn javax.el.ELContext
-dontwarn javax.el.ELResolver
-dontwarn javax.el.ExpressionFactory
-dontwarn javax.el.FunctionMapper
-dontwarn javax.el.ValueExpression
-dontwarn javax.el.VariableMapper
-dontwarn javax.naming.NamingEnumeration
-dontwarn javax.naming.NamingException
-dontwarn javax.naming.directory.Attribute
-dontwarn javax.naming.directory.Attributes
-dontwarn javax.naming.directory.DirContext
-dontwarn javax.naming.directory.InitialDirContext
-dontwarn javax.naming.directory.SearchControls
-dontwarn javax.naming.directory.SearchResult
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.Oid
-dontwarn org.joda.time.Instant

# Keep MBassY event bus classes used by SMBJ
-keepclassmembers class net.engio.** { *; }
-keepnames class net.engio.** { *; }
-keepattributes Signature,*Annotation*

# R8 configuration
#
# Optimization stays disabled to keep the release build close to the debug build
# (fewer surprises from inlining and class merging). Obfuscation is enabled:
# Google Play requires at least 25 % of the DEX code to be obfuscated, otherwise
# the app loses visibility on the store. R8 renaming is deterministic for
# identical inputs, so reproducible F-Droid builds are unaffected.
-dontoptimize

-keepattributes Signature,InnerClasses,EnclosingMethod
# Keep line numbers so that crash stack traces can be mapped back with the
# mapping.txt that CI archives with every build. The source file name is
# replaced by a constant to avoid leaking file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Classes instantiated from persisted names (WorkManager stores the worker class
# name in its database, Room looks up the generated *_Impl database class by name).
# The libraries ship consumer rules for this; they are repeated here on purpose.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# Java serialization: the search result disk cache stores CacheEntry/SmbFileItem via
# ObjectOutputStream, and SmbConnection/SyncConfig are Serializable as well. The
# stream carries class and field names, so keep them stable across releases;
# otherwise a cache written by 2.5.5 would deserialize into objects with null fields.
-keepnames class * implements java.io.Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# keep third-party libs intact
-keep class com.hierynomus.** { *; }
-keep interface com.hierynomus.** { *; }

# BouncyCastle registers its algorithm implementations by class name and
# instantiates them via reflection; renaming them breaks SMB signing/encryption.
-keep class org.bouncycastle.** { *; }
-keep interface org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Material Components, AppCompat, Room, WorkManager, Tink etc. ship their own
# consumer rules, and views referenced from layouts are kept by the rules that
# AAPT generates. No blanket keeps for them.