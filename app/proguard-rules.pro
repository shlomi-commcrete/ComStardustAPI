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

# Keep all of Apache POI and OpenXML schemas
-keep class org.apache.poi.** { *; }
-keep class org.openxmlformats.schemas.** { *; }
-dontwarn org.openxmlformats.schemas.**
-dontwarn org.apache.xmlbeans.**

# If you're using shaded XMLBeans from poi-ooxml-full
-keep class schemaorg_apache_xmlbeans.** { *; }
-dontwarn schemaorg_apache_xmlbeans.**

# Keep everything in com.commcrete.stardust and its subpackages
-keep class com.commcrete.stardust.** { *; }

# Keep enum values (important if you're using them by ordinal or name)
-keepclassmembers enum com.commcrete.stardust.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# If you're using reflection (e.g., by class name)
-keepnames class com.commcrete.stardust.**

# Optional: Keep constructors if you're creating classes via reflection
-keepclassmembers class com.commcrete.stardust.** {
    public <init>(...);
}