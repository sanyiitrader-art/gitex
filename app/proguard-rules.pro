# ---- Gitex R8 rules ----

# JGit loads translated messages by reflecting over public fields.
-keep class org.eclipse.jgit.internal.JGitText { *; }
-keep class * extends org.eclipse.jgit.nls.TranslationBundle { *; }

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

# Editor widget is created from code.
-keep class io.github.rosemoe.sora.widget.CodeEditor { *; }

# BuildConfig (OAuth client id).
-keep class app.gitex.BuildConfig { *; }

# Readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile