package com.goodwy.filemanager.views

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatEditText
import com.alexvasilkov.gestures.GestureController
import com.alexvasilkov.gestures.State
import com.alexvasilkov.gestures.views.interfaces.GestureView
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.onGlobalLayout
import com.goodwy.filemanager.extensions.config

// inspired by
// https://github.com/alexvasilkov/GestureViews/blob/f0a4c266e31dcad23bd0d9013531bc1c501b9c9f/sample/src/main/java/com/alexvasilkov/gestures/sample/ex/custom/text/GestureTextView.java
class GestureEditText : AppCompatEditText, GestureView {
    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(context, attrs, defStyle)

    private val controller: GestureController = GestureController(this)
    private var origSize = 0f
    private var size = 0f

    // when this view's own width is content-driven (e.g. wrapped in a HorizontalScrollView with
    // layout_width="wrap_content" so long lines can scroll instead of wrapping), syncing the
    // gesture controller's viewport/image to the view's own onSizeChanged bounds creates a
    // feedback loop: text size -> desired width -> onSizeChanged -> controller state -> text size.
    // Disable the sync in that case; pinch-zoom simply isn't supported alongside horizontal scroll.
    var isZoomSyncEnabled = true

    init {
        controller.settings.setOverzoomFactor(1f).isPanEnabled = false
        controller.addOnStateChangeListener(object : GestureController.OnStateChangeListener {
            override fun onStateChanged(state: State) {
                applyState(state)
            }

            override fun onStateReset(oldState: State, newState: State) {
                applyState(newState)
            }
        })

        origSize = textSize
        setTextColor(context.getProperTextColor())
        setLinkTextColor(context.getProperPrimaryColor())

        val storedTextZoom = context.config.editorTextZoom
        if (storedTextZoom != 0f) {
            onGlobalLayout {
                if (isZoomSyncEnabled) {
                    controller.state.zoomTo(storedTextZoom, width / 2f, height / 2f)
                    controller.updateState()
                }
            }
        }
    }

    override fun getController() = controller

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isZoomSyncEnabled) {
            controller.onTouch(this, event)
        }
        return super.onTouchEvent(event)
    }

    override fun setTextSize(size: Float) {
        super.setTextSize(size)
        origSize = textSize
        applyState(controller.state)
    }

    override fun setTextSize(unit: Int, size: Float) {
        super.setTextSize(unit, size)
        origSize = textSize
        applyState(controller.state)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (isZoomSyncEnabled) {
            controller.settings.setViewport(width, height).setImage(width, height)
            controller.updateState()
        }
    }

    private fun applyState(state: State) {
        var size = origSize * state.zoom
        val maxSize = origSize * controller.stateController.getMaxZoom(state)
        size = Math.max(origSize, Math.min(size, maxSize))

        size = Math.round(size).toFloat()
        if (!State.equals(this.size, size)) {
            this.size = size
            super.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
            context.config.editorTextZoom = state.zoom
        }
    }
}
