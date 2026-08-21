/**
 *
 * @position Principal Software Engineer - Android
 * @project ${PROJECT_NAME}
 * @date Created on ${DATE} ${TIME}
 * @see "<a href="https://github.com/ProHussain">Github Profile</a>"
 * @see "<a href="https://linkedin.com/in/prohussain/">Linkedin Profile</a>"
 */
package com.umer_tf.ads.domain.ads.app_open

import android.app.Application
import com.umer_tf.ads.domain.diagnostics.AdFormat
import com.umer_tf.ads.domain.utils.AdController

/**
 * The background-to-foreground app-open slot, requested with [AdController.appOpenAdResumeId].
 *
 * A host that drives resume ads itself must call `setShouldShowResumeAd(false)`, otherwise this
 * slot and the host's own flow both react to the same foreground event. One app shipped with the
 * SDK path left on *and* no resume unit configured, so it requested against an empty id on every
 * resume - the blank-id guard in [AppOpenSlotManager] now turns that into a logged no-op.
 */
internal class ResumeAdManager(
    application: Application,
    private val adController: AdController
) : AppOpenSlotManager(
    application = application,
    format = AdFormat.APP_OPEN_RESUME,
    analyticsLabel = "OpenAd_Resume",
    gateOwner = "app_open_resume",
) {
    override fun adUnitId(): String = adController.appOpenAdResumeId
}
