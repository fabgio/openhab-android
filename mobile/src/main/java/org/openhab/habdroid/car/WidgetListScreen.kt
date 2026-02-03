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

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import kotlin.collections.forEach
import kotlin.collections.get
import org.openhab.habdroid.R
import org.openhab.habdroid.model.Item
import org.openhab.habdroid.model.LabeledValue
import org.openhab.habdroid.model.LinkedPage
import org.openhab.habdroid.model.Widget
import org.openhab.habdroid.ui.shouldRenderAsPlayer

class WidgetListScreen(
    carContext: CarContext,
    val url: String,
    private val canGoBack: Boolean,
    private var title: String,
    private val onPageSelected: (page: LinkedPage) -> Unit,
    private val onWidgetCommand: (widget: Widget, command: String) -> Unit,
) : Screen(carContext) {
    private var widgets: MutableList<Widget>? = null
    private val widgetsById = mutableMapOf<String, Widget>()
    private val widgetsByParentId = mutableMapOf<String, MutableList<Widget>>()

    fun updateWidgets(widgets: List<Widget>, title: String?) {
        this.widgets = widgets.toMutableList()
        title?.let { this.title = it }
        widgetsById.clear()
        widgetsByParentId.clear()
        widgets.forEach { w ->
            widgetsById[w.id] = w
            w.parentId
                ?.let { parentId -> widgetsByParentId.getOrPut(parentId) { mutableListOf() } }
                ?.add(w)
        }
        invalidate()
    }

    fun updateWidget(widget: Widget) {
        widgets?.let { widgets ->
            val pos = widgets.indexOfFirst { w -> w.id == widget.id }
            if (pos >= 0) {
                val oldWidget = widgets[pos]
                widgets[pos] = widget
                widgetsById[widget.id] = widget
                widgetsByParentId[oldWidget.parentId]?.remove(oldWidget)
                widget.parentId
                    ?.let { parentId -> widgetsByParentId.getOrPut(parentId) { mutableListOf() } }
                    ?.add(widget)
                invalidate()
            }
        }
    }

    fun updateTitle(title: String) {
        this.title = title
        invalidate()
    }

    private tailrec fun shouldShowWidget(widget: Widget): Boolean {
        if (!widget.visibility) {
            return false
        }
        if (widget.type == Widget.Type.Frame) {
            val hasVisibleChildren = widgets
                ?.filter { it.parentId == widget.id }
                ?.any { it.visibility }
                ?: false
            if (!hasVisibleChildren) {
                return false
            }
        }
        val parent = widget.parentId?.let { id -> widgetsById[id] } ?: return true
        return shouldShowWidget(parent)
    }

    private fun buildWidgetRow(widget: Widget): Row {
        val presentation = when (widget.type) {
            Widget.Type.Switch -> when {
                widget.shouldRenderAsPlayer() ->
                    buildPlayerPresentation(widget)
                widget.mappings.isNotEmpty() ->
                    buildSectionSwitchPresentation(widget, widget.mappings)
                widget.item?.isOfTypeOrGroupType(Item.Type.Switch) == true ->
                    buildToggleSwitchPresentation(widget)
                widget.item?.isOfTypeOrGroupType(Item.Type.Rollershutter) == true ->
                    buildRollerShutterPresentation(widget)
                widget.mappingsOrItemOptions.isNotEmpty() ->
                    buildSectionSwitchPresentation(widget, widget.mappingsOrItemOptions)
                else ->
                    buildToggleSwitchPresentation(widget)
            }

            Widget.Type.Selection -> buildSelectionPresentation(widget)

            else -> if (widget.linkedPage != null) {
                WidgetPresentation.PageLinkPresentation(widget, widget.linkedPage)
            } else {
                WidgetPresentation.TextPresentation(widget)
            }
        }

        val rowBuilder = Row.Builder()
            .setTitle(widget.label)

        widget.stateFromLabel?.replace("\n", " ")?.let { rowBuilder.addText(it) }

        when (presentation) {
            is WidgetPresentation.TogglePresentation -> {
                rowBuilder.setToggle(
                    Toggle.Builder(presentation.listener)
                        .setChecked(presentation.checked)
                        .build()
                )
            }

            is WidgetPresentation.SelectionPresentation -> {
                rowBuilder
                    .setOnClickListener { openSelectionScreen(widget, presentation.options) }
                    .setBrowsable(true)
            }

            is WidgetPresentation.ActionListPresentation -> {
                rowBuilder
                    .setOnClickListener { openActionListScreen(widget, presentation.actions) }
                    .setBrowsable(true)
            }

            is WidgetPresentation.PageLinkPresentation -> {
                rowBuilder
                    .setOnClickListener { onPageSelected(presentation.page) }
                    .setBrowsable(true)
            }

            is WidgetPresentation.TextPresentation -> {}
        }

        return rowBuilder.build()
    }

    private fun buildToggleSwitchPresentation(widget: Widget) =
        WidgetPresentation.TogglePresentation(widget, widget.item?.state?.asBoolean == true) { checked ->
            onWidgetCommand(widget, if (checked) "ON" else "OFF")
        }

    private fun buildSectionSwitchPresentation(widget: Widget, mappings: List<LabeledValue>): WidgetPresentation {
        val actions = mappings.map { MappingActionListItem(it) }
        return WidgetPresentation.ActionListPresentation(widget, actions)
    }

    private fun buildRollerShutterPresentation(widget: Widget): WidgetPresentation {
        val actions = listOf(
            InternalActionListItem(
                R.string.car_action_rollershutter_open,
                R.drawable.ic_keyboard_arrow_up_themed_24dp,
                "UP"
            ),
            InternalActionListItem(
                R.string.car_action_rollershutter_stop,
                R.drawable.ic_clear_themed_24dp,
                "STOP"
            ),
            InternalActionListItem(
                R.string.car_action_rollershutter_close,
                R.drawable.ic_keyboard_arrow_down_themed_24dp,
                "DOWN"
            )
        )
        return WidgetPresentation.ActionListPresentation(widget, actions)
    }

    private fun buildPlayerPresentation(widget: Widget): WidgetPresentation {
        val actions = listOf(
            InternalActionListItem(
                R.string.car_action_player_prev,
                R.drawable.ic_previous_track_themed_24dp,
                "PREVIOUS"
            ),
            InternalActionListItem(
                R.string.car_action_player_play,
                R.drawable.ic_play_themed_24dp,
                "PLAY"
            ),
            InternalActionListItem(
                R.string.car_action_player_pause,
                R.drawable.ic_pause_themed_24dp,
                "PAUSE"
            ),
            InternalActionListItem(
                R.string.car_action_player_next,
                R.drawable.ic_next_track_themed_24dp,
                "NEXT"
            )
        )
        return WidgetPresentation.ActionListPresentation(widget, actions)
    }

    private fun buildSelectionPresentation(widget: Widget): WidgetPresentation {
        val commands = widget.mappingsOrItemOptions.map { SelectionListItem(it.label, it.value) }
        return WidgetPresentation.SelectionPresentation(widget, commands)
    }

    private fun openActionListScreen(widget: Widget, actions: List<ActionListItem>) {
        val screen = ActionListScreen(carContext, widget.label, actions) { item ->
            onWidgetCommand(widget, item.command)
        }
        screenManager.push(screen)
    }

    private fun openSelectionScreen(widget: Widget, options: List<SelectionListItem>) {
        val selected = options.indexOfFirst { widget.state?.asString == it.command }
        val screen = SelectionScreen(carContext, widget.label, options, selected) { item ->
            onWidgetCommand(widget, item.command)
        }
        screenManager.push(screen)
    }

    override fun onGetTemplate(): Template {
        val widgetsToShow = widgets
            ?.filter { shouldShowWidget(it) }
        val frames = widgetsToShow?.filter { it.type == Widget.Type.Frame }

        val headerBuilder = Header.Builder()
            .setTitle(title)

        if (canGoBack) {
            headerBuilder.setStartHeaderAction(Action.BACK)
        }

        val templateBuilder = ListTemplate.Builder()
            .setHeader(headerBuilder.build())

        when {
            frames?.isNotEmpty() == true -> {
                // FIXME: This assumes all widgets are part of a frame
                frames.forEach { frame ->
                    val listBuilder = ItemList.Builder()
                    widgetsToShow
                        .filter { it.parentId == frame.id }
                        .forEach { listBuilder.addItem(buildWidgetRow(it)) }
                    templateBuilder.addSectionedList(
                        SectionedItemList.create(listBuilder.build(), frame.label)
                    )
                }
            }

            widgetsToShow != null -> {
                val listBuilder = ItemList.Builder()
                widgetsToShow.forEach { w ->
                    listBuilder.addItem(buildWidgetRow(w))
                }
                templateBuilder.setSingleList(listBuilder.build())
            }

            else -> {
                templateBuilder.setLoading(true)
            }
        }

        return templateBuilder.build()
    }

    sealed class WidgetPresentation(val widget: Widget) {
        class TextPresentation(widget: Widget) : WidgetPresentation(widget)
        class PageLinkPresentation(widget: Widget, val page: LinkedPage) : WidgetPresentation(widget)
        class TogglePresentation(widget: Widget, val checked: Boolean, val listener: Toggle.OnCheckedChangeListener) :
            WidgetPresentation(widget)
        class SelectionPresentation(widget: Widget, val options: List<SelectionListItem>) :
            WidgetPresentation(widget)
        class ActionListPresentation(widget: Widget, val actions: List<ActionListItem>) :
            WidgetPresentation(widget)
    }
}
