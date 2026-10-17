# Gson and Moshi read JSON model fields and generic type signatures at runtime.
-keepattributes Signature,*Annotation*
-keepclassmembers class com.google.ai.edge.gallery.data.** { <fields>; }
-keepclassmembers class com.google.ai.edge.gallery.ui.home.ReleaseInfo { <fields>; }

# Moshi looks up generated adapters by class name at runtime for JavaScript skill results.
-keepnames class com.google.ai.edge.gallery.tools.CallJsSkillResult*
-keep class com.google.ai.edge.gallery.tools.CallJsSkillResult*JsonAdapter { *; }

# These classes are loaded from literal class names for notifications and workers.
-keepnames class com.google.ai.edge.gallery.MainActivity
-keepnames class com.google.ai.edge.gallery.notifications.NotificationReceiver
