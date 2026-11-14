package com.example.core.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

class NekoCompactWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        NekoWidgetManager.refreshAll(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        NekoWidgetManager.refreshAll(context)
    }
}
