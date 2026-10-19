# Shipped inside the AAR and applied when a host app runs R8.
# Keep this file small. A blanket -keep on the ads SDK disables the shrinking
# the SDK's own consumer rules explicitly allow (allowshrinking on ads_mobile_sdk.**).

# Crashlytics and Play console stack traces stay readable after obfuscation.
-keepattributes SourceFile,LineNumberTable

# Signatures and annotations R8 needs for Kotlin default methods, @Keep and Gson
# models the Next-Gen SDK reflects on. The SDK already keeps its own models;
# these attributes are cheap and required for the library's public Kotlin API.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# Instantiated from the merged manifest, including when the host never names the class.
-keep class com.umer_tf.ads.domain.ads.native_ad.FullScreenNativeAdActivity { *; }

# Named from this library's layouts. allowshrinking so an app that never inflates
# native ads can still drop the view classes. Constructors are listed explicitly:
# AGP 9's strict R8 mode no longer keeps <init> when a class is kept without members.
-keep,allowshrinking class com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView {
    public <init>(...);
}
-keep,allowshrinking class com.google.android.libraries.ads.mobile.sdk.nativead.MediaView {
    public <init>(...);
}
-keep,allowshrinking class com.facebook.shimmer.ShimmerFrameLayout {
    public <init>(...);
}

# Facebook Shimmer references an annotation that is not on the classpath.
-dontwarn com.facebook.infer.annotation.**
