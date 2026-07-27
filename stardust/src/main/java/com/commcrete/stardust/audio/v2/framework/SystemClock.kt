package com.commcrete.stardust.audio.v2.framework

import com.commcrete.stardust.audio.v2.application.port.Clock

/** Framework ring — real wall clock behind the [Clock] port. */
class SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}
