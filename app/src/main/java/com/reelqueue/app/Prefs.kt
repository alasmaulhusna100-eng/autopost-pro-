package com.reelqueue.app

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("reelqueue", Context.MODE_PRIVATE)

    fun pageId(c: Context): String = sp(c).getString("pageId", "") ?: ""
    fun token(c: Context): String = sp(c).getString("token", "") ?: ""

    fun save(c: Context, pageId: String, token: String) {
        sp(c).edit().putString("pageId", pageId.trim()).putString("token", token.trim()).apply()
    }
}
