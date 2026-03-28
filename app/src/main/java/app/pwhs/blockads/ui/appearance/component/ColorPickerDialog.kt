package app.pwhs.blockads.ui.appearance.component

import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R

@Composable
fun ColorPickerDialog(
    initialColor: Color = Color.Red,
    onDismiss: () -> Unit,
    onColorSelected: (Color) -> Unit
) {
    // Decompose initial color to HSV
    val hsv = remember {
        val arr = FloatArray(3)
        android.graphics.Color.colorToHSV(initialColor.toArgb(), arr)
        arr
    }

    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var saturation by remember { mutableFloatStateOf(hsv[1]) }
    var brightness by remember { mutableFloatStateOf(hsv[2]) }

    val currentColor = remember(hue, saturation, brightness) {
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness)))
    }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.background,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custom_color_title)) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Saturation-Brightness panel
                SaturationBrightnessPanel(
                    hue = hue,
                    saturation = saturation,
                    brightness = brightness,
                    onSaturationBrightnessChanged = { s, b ->
                        saturation = s
                        brightness = b
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Hue slider
                HueBar(
                    hue = hue,
                    onHueChanged = { hue = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Preview
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        "Preview",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(currentColor)
                            .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    val hex = String.format("#%06X", 0xFFFFFF and currentColor.toArgb())
                    Text(
                        hex,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onColorSelected(currentColor) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun SaturationBrightnessPanel(
    hue: Float,
    saturation: Float,
    brightness: Float,
    onSaturationBrightnessChanged: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val s = (offset.x / size.width).coerceIn(0f, 1f)
                        val b = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                        onSaturationBrightnessChanged(s, b)
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        val s = (change.position.x / size.width).coerceIn(0f, 1f)
                        val b = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                        onSaturationBrightnessChanged(s, b)
                    }
                }
        ) {
            drawIntoCanvas { canvas ->
                val hueColor = android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))

                // Horizontal: white → hue color (saturation)
                val satShader = LinearGradient(
                    0f, 0f, size.width, 0f,
                    android.graphics.Color.WHITE, hueColor,
                    Shader.TileMode.CLAMP
                )
                // Vertical: transparent → black (brightness)
                val valShader = LinearGradient(
                    0f, 0f, 0f, size.height,
                    android.graphics.Color.TRANSPARENT, android.graphics.Color.BLACK,
                    Shader.TileMode.CLAMP
                )

                val composeShader = ComposeShader(satShader, valShader, PorterDuff.Mode.DARKEN)
                val paint = Paint().apply { shader = composeShader }
                canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
            }

            // Draw cursor
            val cx = saturation * size.width
            val cy = (1f - brightness) * size.height
            drawCircle(
                color = Color.White,
                radius = 10.dp.toPx(),
                center = Offset(cx, cy),
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.3f),
                radius = 10.dp.toPx(),
                center = Offset(cx, cy),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
            )
        }
    }
}

@Composable
private fun HueBar(
    hue: Float,
    onHueChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val hueColors = remember {
        List(361) { i ->
            Color(android.graphics.Color.HSVToColor(floatArrayOf(i.toFloat(), 1f, 1f)))
        }
    }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        onHueChanged((offset.x / size.width * 360f).coerceIn(0f, 360f))
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        onHueChanged((change.position.x / size.width * 360f).coerceIn(0f, 360f))
                    }
                }
        ) {
            drawRect(brush = Brush.horizontalGradient(hueColors))

            // Draw indicator
            val x = hue / 360f * size.width
            drawCircle(
                color = Color.White,
                radius = size.height / 2f,
                center = Offset(x, size.height / 2f),
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.3f),
                radius = size.height / 2f,
                center = Offset(x, size.height / 2f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
            )
        }
    }
}
