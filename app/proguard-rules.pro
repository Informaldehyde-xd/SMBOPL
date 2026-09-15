# JFileServer / Spring Extensions config framework use reflection-based
# instantiation that R8 can't see statically — keep it all intact.
-keep class org.filesys.** { *; }
-keep class org.springframework.extensions.** { *; }
-dontwarn org.filesys.**
-dontwarn org.springframework.extensions.**

# Bouncy Castle similarly relies on reflection for algorithm providers
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**