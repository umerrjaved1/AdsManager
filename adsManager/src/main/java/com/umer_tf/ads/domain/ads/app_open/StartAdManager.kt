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
 * The cold-start app-open slot, requested with [AdController.appOpenAdStartId].
 *
 * All behaviour lives in [AppOpenSlotManager]; this class only binds the unit and the labels.
 */
internal class StartAdManager(
    application: Application,
    private val adController: AdController
) : AppOpenSlotManager(
    application = application,
    format = AdFormat.APP_OPEN_START,
    analyticsLabel = "OpenAd_Start",
    gateOwner = "app_open_start",
) {
    override fun adUnitId(): String = adController.appOpenAdStartId
}
