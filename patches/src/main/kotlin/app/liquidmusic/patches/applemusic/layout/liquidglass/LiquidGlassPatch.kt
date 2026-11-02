/*
 * LiquidMusic - Liquid Glass for Apple Music (patches compatible with Morphe).
 * Licensed under the GNU General Public License v3.0, see LICENSE.
 */
package app.liquidmusic.patches.applemusic.layout.liquidglass

import app.liquidmusic.patches.applemusic.shared.Constants.COMPATIBILITY_APPLE_MUSIC
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.intOption
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.returnEarly
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS = "Lapp/liquidmusic/extension/LiquidGlassPatch;"

@Suppress("unused")
val liquidGlassPatch = bytecodePatch(
    name = "Liquid Glass",
    description = "Replaces the blur behind the floating tab bar and the mini player with " +
        "Liquid Glass: refracted edges, blur, vibrancy and a specular rim. " +
        "Refraction requires Android 13, blur and vibrancy Android 12.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_APPLE_MUSIC)

    extendWith("extensions/liquidglass.mpe")

    val blurRadius by intOption(
        key = "blurRadius",
        default = 8,
        values = mapOf(
            "Subtle" to 4,
            "Default" to 8,
            "Frosted" to 16,
        ),
        title = "Blur radius",
        description = "Blur of the content behind the glass, in dp.",
        required = true,
    ) { it != null && it in 0..64 }

    val refractionHeight by intOption(
        key = "refractionHeight",
        default = 24,
        values = mapOf(
            "Off" to 0,
            "Thin" to 12,
            "Default" to 24,
            "Thick" to 32,
        ),
        title = "Refraction width",
        description = "Width of the bent band along the edges of the glass, in dp. 0 disables the refraction.",
        required = true,
    ) { it != null && it in 0..64 }

    val refractionAmount by intOption(
        key = "refractionAmount",
        default = 24,
        values = mapOf(
            "Subtle" to 12,
            "Default" to 24,
            "Strong" to 40,
        ),
        title = "Refraction strength",
        description = "How far the content is displaced at the very edge of the glass, in dp.",
        required = true,
    ) { it != null && it in 0..96 }

    val saturation by intOption(
        key = "saturation",
        default = 150,
        values = mapOf(
            "Unchanged" to 100,
            "Default" to 150,
            "Vivid" to 200,
        ),
        title = "Vibrancy",
        description = "Saturation of the content behind the glass, in percent. 100 keeps the colors unchanged.",
        required = true,
    ) { it != null && it in 0..300 }

    val tintOpacity by intOption(
        key = "tintOpacity",
        default = 40,
        values = mapOf(
            "Clear" to 0,
            "Default" to 40,
            "Apple default" to 100,
        ),
        title = "Tint opacity",
        description = "Opacity kept from the translucent material Apple Music draws over the tab bar " +
            "and the mini player, in percent. Lower values show more of the glass.",
        required = true,
    ) { it != null && it in 0..100 }

    val highlight by intOption(
        key = "highlight",
        default = 100,
        values = mapOf(
            "Off" to 0,
            "Soft" to 50,
            "Default" to 100,
        ),
        title = "Edge highlight",
        description = "Intensity of the light reflected on the rim of the glass, in percent.",
        required = true,
    ) { it != null && it in 0..100 }

    val chromaticAberration by booleanOption(
        key = "chromaticAberration",
        default = false,
        title = "Chromatic aberration",
        description = "Splits the colors of the refracted edges, like a prism (Android 13+).",
    )

    val applyToSearchBar by booleanOption(
        key = "applyToSearchBar",
        default = true,
        title = "Search bar",
        description = "Also applies the glass to the floating search bar and to the top bar on tablets.",
    )

    execute {
        // Draw the glass instead of the original blur.
        //
        // BlurView.draw(Canvas) becomes:
        //
        //   if (LiquidGlassPatch.drawBlurView(this, canvas)) {
        //       super.draw(canvas);   // background and children of the BlurView
        //       return;
        //   }
        //   ...original code: blurController.draw(canvas), then super.draw(canvas)
        BlurViewDrawFingerprint.let { fingerprint ->
            val superDraw = fingerprint.instructionMatches.last().methodReference.toSmali()

            fingerprint.method.apply {
                val registerCount = implementation!!.registerCount
                val thisRegister = registerCount - 2
                val canvasRegister = registerCount - 1
                val resultRegister = findFreeRegister(0, thisRegister, canvasRegister)

                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static/range { p0 .. p1 }, $EXTENSION_CLASS->drawBlurView(Landroid/view/View;Landroid/graphics/Canvas;)Z
                        move-result v$resultRegister
                        if-eqz v$resultRegister, :original
                        invoke-super/range { p0 .. p1 }, $superDraw
                        return-void
                    """,
                    ExternalLabel("original", getInstruction(0)),
                )
            }
        }

        // Pass the option values to the extension.
        mutableClassDefBy(EXTENSION_CLASS).methods.apply {
            fun method(name: String): MutableMethod = first { it.name == name }

            method("blurRadiusDp").returnEarly(blurRadius!!)
            method("refractionHeightDp").returnEarly(refractionHeight!!)
            method("refractionAmountDp").returnEarly(refractionAmount!!)
            method("saturationPercent").returnEarly(saturation!!)
            method("tintOpacityPercent").returnEarly(tintOpacity!!)
            method("highlightPercent").returnEarly(highlight!!)
            method("chromaticAberration").returnEarly(chromaticAberration == true)
            method("applyToSearchBar").returnEarly(applyToSearchBar != false)
        }
    }
}

private fun MethodReference.toSmali() =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
