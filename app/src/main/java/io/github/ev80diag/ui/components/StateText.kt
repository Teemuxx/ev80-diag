package io.github.ev80diag.ui.components

import io.github.ev80diag.R
import io.github.ev80diag.session.PowerState

fun PowerState.labelRes(): Int = when (this) {
    PowerState.OFF -> R.string.van_state_off
    PowerState.ON -> R.string.van_state_on
    PowerState.READY -> R.string.van_state_ready
    PowerState.PLUGGED -> R.string.van_state_plugged
    PowerState.CHARGING_AC -> R.string.van_state_charging_ac
    PowerState.CHARGING_DC -> R.string.van_state_charging_dc
    PowerState.CHARGE_COMPLETE -> R.string.van_state_complete
    PowerState.STOPPED_BY_BMS -> R.string.van_state_stopped
}
