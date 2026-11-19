package app.pwhs.blockads.ui.component

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import org.intellij.lang.annotations.Language

@Language("AGSL")
private const val LIQUID_GLASS_AGSL = """
uniform shader content;
uniform float2 resolution;
uniform float2 touchPos;
uniform float touchGlow;
uniform float cornerRadius;
uniform float4 rimColor;
uniform float4 tintColor;

half4 main(float2 fragCoord) {
    float2 safeRes = max(resolution, float2(1.0));
    float2 halfSize = 0.5 * safeRes;
    float minDim = min(safeRes.x, safeRes.y);
    float radius = clamp(cornerRadius, 1.0, min(halfSize.x, halfSize.y));

    float2 p = fragCoord - halfSize;
    float2 q = abs(p) - (halfSize - float2(radius));
    float2 qMax = max(q, float2(0.0));
    float qLen = length(qMax);
    float sdf = qLen + min(max(q.x, q.y), 0.0) - radius;

    float insideMask = clamp(0.5 - sdf, 0.0, 1.0);
    float edge = max(-sdf, 0.0);

    // Analytical outward normal; avoid sign(0) zeros along center axes.
    float2 outGrad = (qLen > 1e-4)
        ? (qMax / qLen)
        : ((q.x > q.y) ? float2(1.0, 0.0) : float2(0.0, 1.0));
    float2 sgn = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
    float2 normal = sgn * outGrad;

    // Confine convex bevel refraction strictly to the outer rim so icons and labels stay sharp.
    float bevel = clamp(minDim * 0.20, 4.0, 26.0);
    float rise = clamp(1.0 - edge / bevel, 0.0, 1.0);
    float bevelCurve = rise * rise * rise;
    float shift = bevel * 0.14 * bevelCurve;
    float2 sampleCoord = clamp(
        fragCoord - normal * shift,
        float2(0.5),
        max(safeRes - float2(0.5), float2(0.5))
    );

    float4 base = float4(content.eval(sampleCoord));

    float tintAlpha = clamp(tintColor.a, 0.0, 1.0) * insideMask * (1.0 - 0.45 * base.a);
    float4 outCol;
    outCol.rgb = tintColor.rgb * tintAlpha + base.rgb * (1.0 - tintAlpha);
    outCol.a = tintAlpha + base.a * (1.0 - tintAlpha);

    // Specular rim shine strongest along the top border, fading softly on sides and bottom.
    float rimWidth = clamp(bevel * 0.42, 2.5, 11.0);
    float rimBand = smoothstep(rimWidth, 0.0, edge) * insideMask;
    float crispEdge = smoothstep(2.8, 0.0, edge) * insideMask;
    float rimDirection = mix(0.28, 1.0, clamp(0.5 - 0.5 * normal.y, 0.0, 1.0));
    float rimStrength = clamp((rimBand * 0.65 + crispEdge * 0.45) * rimDirection, 0.0, 1.0);
    float rimAlpha = clamp(rimColor.a * rimStrength, 0.0, 1.0);
    outCol.rgb = rimColor.rgb * rimAlpha + outCol.rgb * (1.0 - rimAlpha * 0.35);
    outCol.a = max(outCol.a, rimAlpha);

    // Gaussian radial touch glow around touchPos scaled by touchGlow.
    float glowRadius = max(minDim * 0.68, 24.0);
    float2 toTouch = fragCoord - touchPos;
    float radial = exp(-dot(toTouch, toTouch) / (glowRadius * glowRadius));
    float glowWeight = clamp(touchGlow, 0.0, 1.0) * (0.80 * radial + 0.14) * insideMask;
    float glowAlpha = clamp(glowWeight * 0.22, 0.0, 0.32);
    outCol.rgb = rimColor.rgb * glowAlpha + outCol.rgb * (1.0 - glowAlpha * 0.25);
    outCol.a = max(outCol.a, glowAlpha * insideMask);

    outCol.a = clamp(outCol.a, 0.0, 1.0) * insideMask;
    outCol.rgb = clamp(outCol.rgb, float3(0.0), float3(outCol.a));
    return half4(outCol);
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object LiquidGlassAgsl {
    fun createShaderOrNull(): RuntimeShader? = runCatching {
        RuntimeShader(LIQUID_GLASS_AGSL)
    }.getOrNull()

    fun buildComposeRenderEffect(
        shader: RuntimeShader,
        width: Float,
        height: Float,
        touchPos: Offset,
        touchGlow: Float,
        cornerRadiusPx: Float,
        rimColor: Color,
        tintColor: Color,
    ): androidx.compose.ui.graphics.RenderEffect? = runCatching {
        val srgbRim = rimColor.convert(ColorSpaces.Srgb)
        val srgbTint = tintColor.convert(ColorSpaces.Srgb)
        shader.setFloatUniform("resolution", width, height)
        shader.setFloatUniform("touchPos", touchPos.x, touchPos.y)
        shader.setFloatUniform("touchGlow", touchGlow)
        shader.setFloatUniform("cornerRadius", cornerRadiusPx)
        shader.setFloatUniform("rimColor", srgbRim.red, srgbRim.green, srgbRim.blue, srgbRim.alpha)
        shader.setFloatUniform("tintColor", srgbTint.red, srgbTint.green, srgbTint.blue, srgbTint.alpha)
        RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }.getOrNull()
}

@Composable
private fun defaultLiquidGlassRimColor(isLens: Boolean): Color {
    val isLight = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    return when {
        isLight && isLens -> Color.White.copy(alpha = 0.85f)
        isLight -> Color.White.copy(alpha = 0.72f)
        isLens -> Color.White.copy(alpha = 0.45f)
        else -> Color.White.copy(alpha = 0.32f)
    }
}

@Composable
private fun defaultLiquidGlassTintColor(isLens: Boolean): Color {
    val isLight = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    return when {
        isLens -> MaterialTheme.colorScheme.primary.copy(alpha = if (isLight) 0.10f else 0.08f)
        isLight -> Color.White.copy(alpha = 0.14f)
        else -> Color.White.copy(alpha = 0.05f)
    }
}

/**
 * Applies an AGSL liquid-glass bevel, specular rim highlight, and touch glow to a floating bar
 * on Android 13+ (API 33+), falling back cleanly to a no-op on API 24-32.
 */
@Composable
fun Modifier.liquidGlassBar(
    touchPos: Offset = Offset.Unspecified,
    touchGlow: Float = 0f,
    cornerRadius: Dp? = null,
    rimColor: Color = defaultLiquidGlassRimColor(isLens = false),
    tintColor: Color = defaultLiquidGlassTintColor(isLens = false),
): Modifier = applyLiquidGlass(
    touchPos = touchPos,
    touchGlow = touchGlow,
    cornerRadius = cornerRadius,
    rimColor = rimColor,
    tintColor = tintColor,
)

/**
 * Applies an AGSL liquid-glass rim sheen and touch highlight to the floating selection lens
 * on Android 13+ (API 33+), falling back cleanly to a no-op on API 24-32.
 */
@Composable
fun Modifier.liquidGlassLens(
    touchPos: Offset = Offset.Unspecified,
    touchGlow: Float = 0f,
    cornerRadius: Dp? = null,
    rimColor: Color = defaultLiquidGlassRimColor(isLens = true),
    tintColor: Color = defaultLiquidGlassTintColor(isLens = true),
): Modifier = applyLiquidGlass(
    touchPos = touchPos,
    touchGlow = touchGlow,
    cornerRadius = cornerRadius,
    rimColor = rimColor,
    tintColor = tintColor,
)

@Composable
private fun Modifier.applyLiquidGlass(
    touchPos: Offset,
    touchGlow: Float,
    cornerRadius: Dp?,
    rimColor: Color,
    tintColor: Color,
): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || LocalInspectionMode.current) {
        return this
    }
    val shader = remember { LiquidGlassAgsl.createShaderOrNull() } ?: return this
    return this.graphicsLayer {
        if (size.isUnspecified || size.width <= 0f || size.height <= 0f) {
            return@graphicsLayer
        }
        val safeTouchPos = if (touchPos.isSpecified) {
            touchPos
        } else {
            Offset(size.width * 0.5f, size.height * 0.5f)
        }
        val radiusPx = (cornerRadius?.toPx() ?: (size.minDimension * 0.5f))
            .coerceIn(1f, size.minDimension * 0.5f)
        renderEffect = LiquidGlassAgsl.buildComposeRenderEffect(
            shader = shader,
            width = size.width,
            height = size.height,
            touchPos = safeTouchPos,
            touchGlow = touchGlow.coerceIn(0f, 1f),
            cornerRadiusPx = radiusPx,
            rimColor = rimColor,
            tintColor = tintColor,
        )
    }
}
