# ---- Gitex R8 rules ----

# JGit does a lot by reflection and service lookup (messages, transports, config). It is kept whole for
# now so Git works reliably; it can be trimmed later once everything is tested.
-keep class org.eclipse.jgit.** { *; }
-keep interface org.eclipse.jgit.** { *; }
-keep class com.googlecode.javaewah.** { *; }

# Classes JGit references that do not exist on Android.
-dontwarn java.lang.ProcessHandle
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn javax.naming.**
-dontwarn javax.annotation.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.**
-dontwarn com.googlecode.javaewah.**
-dontwarn org.eclipse.jgit.**

# Sora Editor: Gitex adjusts it by name (colour ids, settings, internals), so
# nothing in it may be renamed or removed by R8.
-keep class io.github.rosemoe.sora.** { *; }
-keep interface io.github.rosemoe.sora.** { *; }
-dontwarn io.github.rosemoe.sora.**

# Markwon
-dontwarn io.noties.markwon.**

# BuildConfig (OAuth client id).
-keep class app.gitex.BuildConfig { *; }

# Readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile