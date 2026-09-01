# Keep WebView clients
-keepclassmembers class * extends android.webkit.WebViewClient { public *; }
-keepclassmembers class * extends android.webkit.WebChromeClient { public *; }
-dontwarn android.webkit.**
# shrink aggressively
-optimizationpasses 5
-allowaccessmodification
-repackageclasses
