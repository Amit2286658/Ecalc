package com.ecalc

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewTreeObserver
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.widget.CompoundButtonCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.ecalc.databinding.ActivityMainBinding
import kotlin.math.hypot


fun MainActivity.onKeyClicked(label: String) {
    fun flushOperand() {
        if (currentPrimaryInput != "0.0") {
            exprStatement += currentPrimaryInput
            currentPrimaryInput = "0.0"
        }
    }

    fun withOperator(opDisplay: String, spaced: Boolean = false) {
        val tail = if (spaced) "$opDisplay " else opDisplay
        val trimmed = exprStatement.trimEnd()
        exprStatement = if (trimmed.isNotEmpty() && trimmed.last() in "+-×÷^") {
            "${trimmed.dropLast(1)}$tail"
        } else { "$trimmed$tail" }
    }

    when (label) {
        "AC" -> {
            exprStatement = ""; currentPrimaryInput = "0.0"; currentSecondaryFormula = "0.0"
            binding.tvPrimaryDisplay.text = "0.0"; binding.tvSecondaryDisplay.text = "0.0"
            displaySpringPulse(binding.tvPrimaryDisplay, 0.05f)
            displaySpringPulse(binding.tvSecondaryDisplay, 0.035f)
            return
        }
        "⌫" -> {
            when {
                currentPrimaryInput.length > 1 && currentPrimaryInput != "0.0" ->
                    currentPrimaryInput = currentPrimaryInput.dropLast(1)
                currentPrimaryInput != "0.0" -> currentPrimaryInput = "0.0"
                else -> {
                    var s = exprStatement.trimEnd()
                    val endedWithOpenParen = s.endsWith("(")
                    if (s.isNotEmpty()) s = s.dropLast(1).trimEnd()
                    if (endedWithOpenParen) {
                        val trailIdent = Regex("[a-zA-Z_][a-zA-Z0-9_]*$").find(s)
                        if (trailIdent != null) {
                            s = s.substring(0, trailIdent.range.first).trimEnd()
                        } else {
                            val heads = listOf("ⁿ√", "√", "log₂", "1/", "10^")
                            for (h in heads) { if (s.endsWith(h)) { s = s.removeSuffix(h).trimEnd(); break } }
                        }
                    }
                    exprStatement = s
                }
            }
        }
        "M+" -> {
            val value = currentPrimaryInput.toDoubleOrNull() ?: 0.0
            memoryValue += value
            val displayStr = formatValue(memoryValue)
            binding.tvMrValue.text = displayStr
            displaySpringPulse(binding.tvMrValue, 0.05f)
            return
        }
        "M-" -> {
            val value = currentPrimaryInput.toDoubleOrNull() ?: 0.0
            memoryValue -= value
            val displayStr = formatValue(memoryValue)
            binding.tvMrValue.text = displayStr
            displaySpringPulse(binding.tvMrValue, 0.05f)
            return
        }
        "=" -> { evaluateCurrent(); return }
        "Ans" -> {
            if (currentPrimaryInput == "0.0") currentPrimaryInput = "ans"
            else currentPrimaryInput += "ans"
        }
        "π", "e" -> {
            if (currentPrimaryInput == "0.0") currentPrimaryInput = label
            else currentPrimaryInput += label
        }
        "rand" -> {
            val r = formatValue(Math.random())
            if (currentPrimaryInput != "0.0") {
                flushOperand()
                if (exprStatement.isNotEmpty() && exprStatement.trimEnd().last() !in "+-×÷/*^,(") {
                    exprStatement += "×"
                }
            }
            currentPrimaryInput = r
        }
        "ⁿ√" -> {
            val hasActiveOperand = currentPrimaryInput != "0.0" || exprStatement.trimEnd().endsWith(")")
            if (hasActiveOperand) {
                flushOperand()
                exprStatement += "√("
            } else {
                exprStatement += "ⁿ√("
            }
        }
        "!" -> {
            if (currentPrimaryInput != "0.0") currentPrimaryInput += "!"
            else if (exprStatement.trimEnd().endsWith(")")) exprStatement += "!"
        }
        "x²" -> {
            if (currentPrimaryInput != "0.0") currentPrimaryInput += "^2"
            else if (exprStatement.trimEnd().endsWith(")")) exprStatement += "^2"
        }
        "x³" -> {
            if (currentPrimaryInput != "0.0") currentPrimaryInput += "^3"
            else if (exprStatement.trimEnd().endsWith(")")) exprStatement += "^3"
        }
        "xʸ" -> { flushOperand(); withOperator("^") }
        "10ˣ" -> { flushOperand(); exprStatement += "10^(" }
        "eˣ" -> { flushOperand(); exprStatement += "exp(" }
        "1/x" -> { flushOperand(); exprStatement += "1/(" }
        "%" -> {
            if (currentPrimaryInput != "0.0" && !currentPrimaryInput.endsWith("%")) {
                currentPrimaryInput += "%"
            } else if (currentPrimaryInput == "0.0" && exprStatement.trimEnd().endsWith(")")) {
                exprStatement += "%"
            }
        }
        "+" -> { flushOperand(); withOperator("+") }
        "-" -> { flushOperand(); withOperator("-") }
        "x" -> { flushOperand(); withOperator("×") }
        "÷" -> { flushOperand(); withOperator("/") }
        "^" -> { flushOperand(); withOperator("^") }
        "." -> {
            if (!currentPrimaryInput.contains(".")) {
                currentPrimaryInput = if (currentPrimaryInput == "0.0") "0." else currentPrimaryInput + "."
            }
        }
        "EE" -> {
            if (!currentPrimaryInput.contains('E') && !currentPrimaryInput.contains("ans")) {
                currentPrimaryInput = if (currentPrimaryInput == "0.0") "1EE" else currentPrimaryInput + "EE"
            }
        }
        "0","1","2","3","4","5","6","7","8","9" -> {
            currentPrimaryInput = if (currentPrimaryInput == "0.0" || currentPrimaryInput == "0") label else currentPrimaryInput + label
        }
        "," -> {
            flushOperand()
            val trimmed = exprStatement.trimEnd()
            if (trimmed.isNotEmpty() && trimmed.last() !in "(,+-×÷/*^") {
                exprStatement = "$trimmed,"
            }
        }
        "(" -> { flushOperand(); exprStatement += "(" }
        ")" -> {
            flushOperand()
            val openParens = exprStatement.count { it == '(' } - exprStatement.count { it == ')' }
            if (openParens > 0) {
                val lastOpen = exprStatement.lastIndexOf('(')
                val prefix = if (lastOpen >= 0) exprStatement.substring(0, lastOpen) else ""
                val inside = if (lastOpen >= 0) exprStatement.substring(lastOpen + 1) else ""
                val twoArgHeads = listOf("npr", "ncr", "gcd", "lcm", "root", "ⁿ√")
                if (twoArgHeads.any { prefix.endsWith(it) } && !inside.contains(',')) {
                    exprStatement += ","
                } else {
                    exprStatement += ")"
                }
            }
        }
        else -> {
            if (label.isNotEmpty() && (label[0].isLetterOrDigit() || label[0] == '√')) {
                if (currentPrimaryInput != "0.0") {
                    flushOperand()
                    if (exprStatement.isNotEmpty() && exprStatement.trimEnd().last() !in "+-×÷/*^,(") {
                        exprStatement += "×"
                    }
                }
                val fn = when (label) {
                    "nPr" -> "npr"
                    "nCr" -> "ncr"
                    "log₂" -> "log2"
                    else -> label
                }
                exprStatement += "$fn("
            }
        }
    }
    binding.tvPrimaryDisplay.text =
        if (currentPrimaryInput == "0.0") exprStatement.trimEnd().ifBlank { "0.0" }
        else (exprStatement + currentPrimaryInput).ifBlank { "0.0" }
    displaySpringPulse(binding.tvPrimaryDisplay, 0.05f)
}

internal fun MainActivity.onSmartBracketClicked() {
    val activeOperand = if (currentPrimaryInput != "0.0") currentPrimaryInput else ""
    val tail = (exprStatement + activeOperand).trimEnd()
    val lastChar = tail.lastOrNull()
    val balance = tail.count { it == '(' } - tail.count { it == ')' }
    val operandExpected = lastChar == null || lastChar in "(,+×÷/*^-"
    val shouldClose = balance > 0 && !operandExpected
    if (shouldClose) {
        onKeyClicked(")")
    } else {
        onKeyClicked("(")
    }
}

internal fun MainActivity.evaluateCurrent() {
    val fullDisplay = if (currentPrimaryInput == "0.0") exprStatement
                      else exprStatement + currentPrimaryInput
    // "=" with nothing typed still runs the full engine round-trip on a zero expression so the
    // spring + result reveal always fire, instead of silently early-returning.
    val evalExpr = fullDisplay.ifBlank { "0" }
    val blankEval = fullDisplay.isBlank()

    binding.tvSecondaryDisplay.text = "$evalExpr ="

    // Spring feedback fires at the '=' click itself (expression line + current answer preview),
    // independent of the engine round-trip below — the damped-spring kick the user sees on every
    // equals press, even while evaluation is still in flight on the worker queue.
    displaySpringPulse(binding.tvPrimaryDisplay, 0.05f)
    displaySpringPulse(binding.tvSecondaryDisplay, 0.04f)

    MathEngineManager.evaluate(evalExpr, isDegreeMode, lastAnswer) { outcome ->
        runOnUiThread {
            if (outcome.ok) {
                lastAnswer = outcome.value
                currentPrimaryInput = outcome.value
                exprStatement = ""
                val unitTag = if (outcome.unit != "none") " [${outcome.unit}]" else ""
                val resultLine = "$evalExpr = ${outcome.value}$unitTag"
                // Do not litter History with "0 = 0.0" rows from empty "=" presses.
                if (!blankEval) {
                    calculationHistory.add(0, resultLine)
                    refreshHistoryUI()
                }
                currentSecondaryFormula = resultLine
                binding.tvPrimaryDisplay.text = outcome.value
                binding.tvSecondaryDisplay.text = resultLine
                displaySpringPulse(binding.tvPrimaryDisplay, 0.05f)
                displaySpringPulse(binding.tvSecondaryDisplay, 0.035f)
            } else {
                val msg = outcome.error ?: "Unknown error"
                val errLine = if (msg.length > 40) "⚠ ${msg.take(37)}…" else "Error: $msg"
                currentSecondaryFormula = errLine
                binding.tvSecondaryDisplay.text = errLine
                displaySpringPulse(binding.tvSecondaryDisplay, 0.04f)
            }
        }
    }
}

internal fun MainActivity.displaySpringPulse(view: View, bump: Float) {
    displayPulseAnimators.removeAll { (v, a) -> if (v === view) { a.cancel(); true } else false }
    if (view.width > 0) {
        view.pivotX = view.width.toFloat(); view.pivotY = view.height / 2f
    }
    val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 480L; interpolator = LinearInterpolator()
        addUpdateListener { a ->
            val f = a.animatedValue as Float
            val t = f * 0.48
            val decay = Math.exp(-7.5 * t)
            val pulse = decay * Math.sin(t * 22.0)
            view.scaleX = (1.0 + bump * pulse).toFloat()
            view.scaleY = (1.0 + bump * pulse).toFloat()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                view.scaleX = 1f; view.scaleY = 1f
            }
        })
        start()
    }
    displayPulseAnimators.add(view to anim)
}

internal fun MainActivity.formatValue(v: Double): String {
    if (v % 1.0 == 0.0) return "${v.toLong()}.0"
    var s = String.format("%.4f", v)
    val dotIdx = s.indexOf('.')
    if (dotIdx >= 0) {
        s = s.take(dotIdx + 1) + s.substring(dotIdx + 1).trimEnd('0')
        if (s.endsWith('.')) s = s.dropLast(1)
    }
    return s
}

fun MainActivity.refreshHistoryUI(fadeInPlaceholder: Boolean = false) {
    (fragmentAt(0) as? HistoryFragment)?.refresh(calculationHistory, isDarkMode, fadeInPlaceholder)
}
