package com.guardpal.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Puts Byte back on guard after the phone restarts or the app updates. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (GuardService.wanted(ctx)) GuardService.start(ctx)
    }
}
