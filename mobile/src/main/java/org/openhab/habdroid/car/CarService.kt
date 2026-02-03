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
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.annotation.CallSuper
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ServiceLifecycleDispatcher
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openhab.habdroid.core.connection.CloudConnection
import org.openhab.habdroid.core.connection.Connection
import org.openhab.habdroid.core.connection.ConnectionFactory
import org.openhab.habdroid.model.ServerProperties
import org.openhab.habdroid.model.Widget
import org.openhab.habdroid.ui.activity.PageConnectionHolderFragment
import org.openhab.habdroid.ui.sendItemCommand
import org.openhab.habdroid.util.HttpClient

class CarService :
    CarAppService(),
    LifecycleOwner,
    ConnectionFactory.UpdateListener,
    PageConnectionHolderFragment.ParentCallback {

    private val dispatcher = ServiceLifecycleDispatcher(this)
    override val lifecycle get() = dispatcher.lifecycle
    private val connectionHolder = PageConnectionHolderFragment()

    override val isDetailedLoggingEnabled: Boolean
        get() = false
    override val serverProperties: ServerProperties?
        get() = initDataFlow.value?.getOrNull()?.props

    private data class InitData(val connection: Connection, val props: ServerProperties)

    private val initDataFlow = MutableStateFlow<Result<InitData>?>(null)
    private var initDataLoadJob: Job? = null
    private val sessions = mutableMapOf<String, CarSession>()

    @CallSuper
    override fun onCreate() {
        dispatcher.onServicePreSuperOnCreate()
        super.onCreate()
        connectionHolder.setCallback(this)
        connectionHolder.onStart()
        ConnectionFactory.addListener(this)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    @CallSuper
    override fun onStart(intent: Intent?, startId: Int) {
        dispatcher.onServicePreSuperOnStart()
        super.onStart(intent, startId)
    }

    // this method is added only to annotate it with @CallSuper.
    // In usual Service, super.onStartCommand is no-op, but in LifecycleService
    // it results in dispatcher.onServicePreSuperOnStart() call, because
    // super.onStartCommand calls onStart().
    @CallSuper
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return super.onStartCommand(intent, flags, startId)
    }

    @CallSuper
    override fun onDestroy() {
        dispatcher.onServicePreSuperOnDestroy()
        connectionHolder.onStop()
        ConnectionFactory.removeListener(this)
        super.onDestroy()
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        dispatcher.onServicePreSuperOnBind()
        val session = CarSession(
            initDataFlow.map { result -> result?.map { it.props.sitemaps } },
            onPageListChanged = this::updateConnections,
            onSendWidgetCommand = this::sendWidgetCommand
        )
        sessions[sessionInfo.sessionId] = session
        return session
    }

    override fun createHostValidator(): HostValidator =
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(this)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onActiveConnectionChanged() {
        Log.d(TAG, "Active connection change, trigger init data reload")
        initDataFlow.value = null
        initDataLoadJob?.cancel()
        initDataLoadJob = lifecycleScope.launch {
            initDataFlow.value = initializeConnectionAndLoadData()
        }
    }

    override fun onPrimaryConnectionChanged() {}

    override fun onActiveCloudConnectionChanged(connection: CloudConnection?) {}

    override fun onPrimaryCloudConnectionChanged(connection: CloudConnection?) {}

    override fun onPageUpdated(pageUrl: String, pageTitle: String?, widgets: List<Widget>) {
        sessions.values.forEach { it.handlePageUpdate(pageUrl, pageTitle, widgets) }
    }

    override fun onWidgetUpdated(pageUrl: String, widget: Widget) {
        sessions.values.forEach { it.handleWidgetUpdate(pageUrl, widget) }
    }

    override fun onPageTitleUpdated(pageUrl: String, title: String) {
        sessions.values.forEach { it.handlePageTitleUpdate(pageUrl, title) }
    }

    override fun onLoadFailure(error: HttpClient.HttpException) {
        sessions.values.forEach { it.handleLoadFailure(error) }
    }

    override fun onSseFailure() {
        TODO("Not yet implemented")
    }

    private suspend fun initializeConnectionAndLoadData(): Result<InitData>? {
        val connResult = ConnectionFactory.activeUsableConnection
        return when {
            connResult == null -> null
            connResult.connection != null -> {
                val result = withContext(Dispatchers.IO) {
                    ServerProperties.fetch(connResult.connection)
                }
                when (result) {
                    is ServerProperties.Companion.PropsSuccess -> {
                        Result.success(InitData(connResult.connection, result.props))
                    }

                    is ServerProperties.Companion.PropsFailure ->
                        Result.failure(result.error)
                }
            }

            connResult.failureReason != null -> Result.failure(connResult.failureReason)
            else -> throw IllegalStateException()
        }
    }

    private fun updateConnections() {
        val data = initDataFlow.value?.getOrNull() ?: return
        val pageUrlSet = mutableSetOf<String>()
        sessions.values.forEach { pageUrlSet += it.pageUrls }
        connectionHolder.updateActiveConnections(pageUrlSet.toList(), data.connection)
    }

    private fun sendWidgetCommand(widget: Widget, command: String) {
        val connection = initDataFlow.value?.getOrNull()?.connection ?: return
        connection.httpClient.sendItemCommand(widget.item, command)
    }

    companion object {
        const val TAG = "CarService"
    }
}
