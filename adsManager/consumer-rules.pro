# Consumer ProGuard/R8 rules shipped to every app that depends on this AAR.
#
# Deliberately minimal. The AdMob, Firebase and Shimmer SDKs ship their own consumer rules, and this
# library uses no JNI and no serialised models - so most "recommended" ad-library rule blocks would be
# cargo cult and would only bloat the host app. The one reflective path is ViewModel construction,
# covered below.
#
# How to verify before adding anything here: the sample app now builds with isMinifyEnabled = true, so
#   ./gradlew :app:assembleRelease
# is the check. If something breaks under R8, add the rule *and* a note saying what broke.

# Native ad layouts are inflated from XML, so view classes referenced only from resources must keep
# their inflation constructors or R8 strips them and inflation throws at runtime.
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Public callback/data surface. A host app implements AdEventListener and reads the payload types, and
# the enum names are used as analytics dimensions, so renaming them would silently change reporting.
-keep public interface com.umer_tf.ads.domain.analytics.AdEventListener { *; }
-keep public class com.umer_tf.ads.domain.analytics.AdRevenueInfo { *; }
-keep public class com.umer_tf.ads.domain.analytics.AdLoadFailure { *; }
-keep public enum com.umer_tf.ads.domain.analytics.AdType { *; }
-keep public enum com.umer_tf.ads.domain.analytics.AdValuePrecision { *; }

# AdViewModel is never constructed by the app directly - ViewModelProvider reflects for the
# (Application) constructor, which R8 cannot see being called. androidx.lifecycle ships a rule of its
# own, but relying on that is a bad trade here: it costs one line to be explicit, and the failure mode
# is a crash the first time any ad screen opens, in a release build, in every consuming app.
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel {
    <init>(android.app.Application);
}
