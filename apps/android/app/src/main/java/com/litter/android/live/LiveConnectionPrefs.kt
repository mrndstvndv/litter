package com.litter.android.live

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object LiveConnectionPrefs {
    private const val PREFS = "litter_live_prefs"
    private const val KEY_MODE = "mode"

    var mode by mutableStateOf(Mode.WHILE_WORKING)
        private set

    fun initialize(context: Context) {
        val savedMode = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, Mode.WHILE_WORKING.name)
        mode = Mode.entries.firstOrNull { it.name == savedMode } ?: Mode.WHILE_WORKING
    }

    fun setMode(context: Context, mode: Mode) {
        this.mode = mode
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode.name).apply()
    }
}
