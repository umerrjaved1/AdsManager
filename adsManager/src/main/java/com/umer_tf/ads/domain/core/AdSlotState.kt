package com.umer_tf.ads.domain.core

/**
 * Lifecycle of a single ad slot (one ad unit, one cached ad).
 *
 * The states are deliberately coarse. What matters is not the label but the transitions they
 * forbid: two concurrent loads of one unit, a show while another full-screen ad owns the screen,
 * and - the expensive one - a fill being discarded because whoever asked for it has moved on.
 */
enum class AdSlotState {
    /** Nothing cached, nothing in flight. A request may start. */
    IDLE,

    /** A network request is in flight. Further requests for this unit must join, not duplicate. */
    LOADING,

    /** An ad is cached and displayable. Cleared only by a show, by expiry, or by [destroy]. */
    READY,

    /** The ad is on screen. No other full-screen ad may be shown until it is dismissed. */
    SHOWING,

    /**
     * The last request did not fill. Equivalent to [IDLE] for the purpose of starting a new
     * request - it exists so diagnostics can tell "never asked" from "asked and got nothing".
     */
    FAILED;

    /** True when a fresh network request may be started for this slot. */
    val canRequest: Boolean
        get() = this == IDLE || this == FAILED

    /** True when a request is already in flight and a second caller should join it. */
    val isLoading: Boolean
        get() = this == LOADING
}
