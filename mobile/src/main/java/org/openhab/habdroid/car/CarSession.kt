/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.openhab.habdroid.car

import android.content.Intent
import android.util.Log
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.openhab.habdroid.R
import org.openhab.habdroid.core.connection.ConnectionFactory
import org.openhab.habdroid.model.LinkedPage
import org.openhab.habdroid.model.Sitemap
import org.openhab.habdroid.model.Widget
import org.openhab.habdroid.util.getDefaultCarSitemapName
import org.openhab.habdroid.util.getPrefs
import org.openhab.habdroid.util.updateDefaultCarSitemap

class CarSession(
    sitemapsFlow: Flow<Result<List<Sitemap>>?>,
    private val onPageListChanged: () -> Unit,
    private val onSendWidgetCommand: (widget: Widget, command: String) -> Unit
) : Session() {
    private var latestSitemapResult: Result<List<Sitemap>>? = null
    private val pageStack = mutableListOf<WidgetListScreen>()
    val pageUrls get() = pageStack.map { it.url }

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                sitemapsFlow.collect {
                    Log.d(TAG, "Got new sitemap result $it")
                    latestSitemapResult = it
                    pageStack.clear()

                    val screenManager = carContext.getCarService(ScreenManager::class.java)
                    screenManager.popToRoot()
                    screenManager.push(createScreenForCurrentSitemap(it))
                }
            }
        }
    }

    fun handlePageUpdate(pageUrl: String, pageTitle: String?, widgets: List<Widget>) {
        pageStack.firstOrNull { it.url == pageUrl }
            ?.updateWidgets(widgets, pageTitle)
    }

    fun handleWidgetUpdate(pageUrl: String, widget: Widget) {
        pageStack.firstOrNull { it.url == pageUrl }
            ?.updateWidget(widget)
    }

    fun handlePageTitleUpdate(pageUrl: String, title: String) {
        pageStack.firstOrNull { it.url == pageUrl }
            ?.updateTitle(title)
    }
    fun handleLoadFailure(reason: Throwable?) {
        val screenManager = carContext.getCarService(ScreenManager::class.java)
        screenManager.popToRoot()
        screenManager.push(createErrorScreen(null, reason))
    }

    override fun onCreateScreen(intent: Intent): Screen {
        return createScreenForCurrentSitemap(latestSitemapResult)
    }

    private fun createScreenForCurrentSitemap(result: Result<List<Sitemap>>?): Screen = when {
        result == null -> LoadingScreen(carContext)

        result.isSuccess -> {
            val sitemaps = result.getOrThrow()
            val lastSitemapName = carContext.getPrefs().getDefaultCarSitemapName()
            val selectedSitemap = sitemaps.firstOrNull { it.name == lastSitemapName }
            when {
                selectedSitemap != null ->
                    createWidgetListScreen(selectedSitemap.homepageLink, selectedSitemap.label, false)
                sitemaps.isEmpty() ->
                    createErrorScreen(carContext.getString(R.string.error_empty_sitemap_list), null)
                else ->
                    SitemapSelectionScreen(carContext, sitemaps) { sitemap ->
                        carContext.getPrefs().updateDefaultCarSitemap(sitemap)
                        val screenManager = carContext.getCarService(ScreenManager::class.java)
                        screenManager.popToRoot()
                        screenManager.push(createWidgetListScreen(sitemap.homepageLink, sitemap.label, false))
                    }
            }
        }

        else -> createErrorScreen(null, result.exceptionOrNull())
    }

    private fun createErrorScreen(message: CharSequence?, reason: Throwable?) =
        ErrorScreen(carContext, message, reason) {
            ConnectionFactory.restartNetworkCheck()
        }

    private fun createWidgetListScreen(url: String, title: String, canGoBack: Boolean): WidgetListScreen {
        val screen = WidgetListScreen(
            carContext,
            url,
            canGoBack,
            title,
            onPageSelected = { page -> openWidgetListScreen(page) },
            onWidgetCommand = { widget, command -> onSendWidgetCommand(widget, command) }
        )
        pageStack += screen
        onPageListChanged()
        return screen
    }

    private fun openWidgetListScreen(page: LinkedPage) {
        val screen = createWidgetListScreen(page.link, page.title, true)
        screen.screenManager.push(screen)
    }

    companion object {
        const val TAG = "CarSession"
    }
}
