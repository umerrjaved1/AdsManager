# Applied only if this module's release build has minify enabled.
# Release minify is off: a library AAR is shrunk by the host app, and the rules
# that host needs live in consumer-rules.pro. These rules exist so turning minify
# on here does not strip the public API out of the published AAR.

-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

-keep public class com.umer_tf.ads.domain.** {
    public protected *;
}

-keep class com.umer_tf.ads.domain.ads.native_ad.FullScreenNativeAdActivity { *; }

-dontwarn com.facebook.infer.annotation.**
