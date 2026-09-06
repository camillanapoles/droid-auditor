# Keep the forensic toolchain shell scripts bundled in assets as-is.
-keep class com.camillanapoles.droidauditor.** { *; }

# org.json is part of the platform; nothing extra required.
-dontwarn org.json.**
