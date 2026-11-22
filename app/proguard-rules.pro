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

# Obsfucation is what proprietary software does to keep the user unaware of it's abuses.
# Also it's easier to fix issues if the stack trace symbols remain unmangled.
-dontobfuscate

# Used reflectively by ScaledPlaybackButton to undo MaterialButtonGroup's temporary width
# mutations before applying playback-specific scaling.
-keepclassmembers class com.google.android.material.button.MaterialButton {
    void recoverOriginalLayoutParams();
}

# Used reflectively across MainFragment, FrameworkUtil, and IntListPreference.
-keepclassmembers class com.google.android.material.floatingactionbutton.FloatingActionButton {
    void hide(com.google.android.material.floatingactionbutton.FloatingActionButton$OnVisibilityChangedListener, boolean);
}
-keepclassmembers class androidx.appcompat.widget.AppCompatButton {
    *** mBackgroundTintHelper;
}
-keepclassmembers class androidx.viewpager2.widget.ViewPager2 {
    androidx.recyclerview.widget.RecyclerView mRecyclerView;
}
-keepclassmembers class androidx.recyclerview.widget.RecyclerView {
    int mTouchSlop;
}
-keepclassmembers class androidx.preference.Preference {
    java.lang.Object mDefaultValue;
}

# Make AGP shut up about classes that aren't even used.
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.conscrypt.Conscrypt$Version
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.ConscryptHostnameVerifier
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE

# Rules for Rhino JS engine used by NewPipeExtractor for YouTube signature deciphering.
# Keep core runtime & JS evaluation classes while allowing tools, serialize, xml/xmlimpl to shrink.
-keep class org.mozilla.javascript.* { *; }
-keep class org.mozilla.javascript.annotations.** { *; }
-keep class org.mozilla.javascript.ast.** { *; }
-keep class org.mozilla.javascript.commonjs.** { *; }
-keep class org.mozilla.javascript.dtoa.** { *; }
-keep class org.mozilla.javascript.jdk18.** { *; }
-keep class org.mozilla.javascript.json.** { *; }
-keep class org.mozilla.javascript.optimizer.** { *; }
-keep class org.mozilla.javascript.regexp.** { *; }
-keep class org.mozilla.javascript.typedarrays.** { *; }
-keep class org.mozilla.javascript.v8dtoa.** { *; }
-keep class org.mozilla.classfile.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter { *; }
-dontwarn org.mozilla.javascript.tools.**
-dontwarn org.mozilla.javascript.xml.**
-dontwarn org.mozilla.javascript.xmlimpl.**
-dontwarn org.conscrypt.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**

# Rules for NewPipeExtractor (YouTube-only; allow unused services such as SoundCloud, Bandcamp,
# PeerTube, and MediaCCC to shrink).
-keep class * implements org.schabi.newpipe.extractor.downloader.Downloader { *; }
-keep class org.schabi.newpipe.extractor.NewPipe { *; }
-keep class org.schabi.newpipe.extractor.ServiceList { *; }
-keep class org.schabi.newpipe.extractor.StreamingService { *; }
-keep class org.schabi.newpipe.extractor.StreamingService$* { *; }
-keep class org.schabi.newpipe.extractor.Extractor { *; }
-keep class org.schabi.newpipe.extractor.Info { *; }
-keep class org.schabi.newpipe.extractor.InfoItem { *; }
-keep class org.schabi.newpipe.extractor.InfoItem$* { *; }
-keep class org.schabi.newpipe.extractor.ListExtractor { *; }
-keep class org.schabi.newpipe.extractor.ListExtractor$* { *; }
-keep class org.schabi.newpipe.extractor.Page { *; }
-keep class org.schabi.newpipe.extractor.MediaFormat { *; }
-keep class org.schabi.newpipe.extractor.Image { *; }
-keep class org.schabi.newpipe.extractor.Image$* { *; }
-keep class org.schabi.newpipe.extractor.downloader.** { *; }
-keep class org.schabi.newpipe.extractor.localization.** { *; }
-keep class org.schabi.newpipe.extractor.exceptions.** { *; }
-keep class org.schabi.newpipe.extractor.stream.** { *; }
-keep class org.schabi.newpipe.extractor.search.** { *; }
-keep class org.schabi.newpipe.extractor.linkhandler.** { *; }
-keep class org.schabi.newpipe.extractor.playlist.** { *; }
-keep class org.schabi.newpipe.extractor.channel.** { *; }
-keep class org.schabi.newpipe.extractor.utils.** { *; }
-keep class org.schabi.newpipe.extractor.timeago.* { *; }
-keep class org.schabi.newpipe.extractor.timeago.patterns.en { *; }
-keep class org.schabi.newpipe.extractor.timeago.patterns.es { *; }
-keep class org.schabi.newpipe.extractor.services.youtube.** { *; }
