@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.musicclone

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.launch
import kotlin.math.*

// =====================================================================
// MOTOR EM TEMPO REAL DE LIQUID GLASS (iOS 26/27 Optical Engine)
// Baseado em Kyant0/AndroidLiquidGlass (AGSL RuntimeShader + RenderNode
// + Snell's Law SDF Lens Refraction + 7-Band Chromatic Dispersion + Real-Time Dim)
// + dev.chrisbanes.haze:haze
//
// AJUSTE: menos blur, mais transparente, borda mais fina, refração menor.
// =====================================================================

private const val AGSL_ROUNDED_RECT_SDF = """
float radiusAt(float2 centeredCoord, float4 radii) {
    if (centeredCoord.x >= 0.0) {
        if (centeredCoord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (centeredCoord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        float2 m = max(cornerCoord, 0.0);
        float len = max(length(m), 0.0001);
        return sign(coord) * (m / len);
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}
"""

// Shader AGSL de Refração Óptica + Dispersão Cromática de 7 Bandas + Dim em Tempo Real + Specular Rim
private const val AGSL_LIQUID_GLASS_SHADER = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float chromaticAberration;
uniform float dimAmount;
uniform float pressIntensity;
uniform float4 tintColor;

$AGSL_ROUNDED_RECT_SDF

float circleMap(float x) {
    float cx = clamp(x, 0.0, 1.0);
    return 1.0 - sqrt(max(0.0, 1.0 - cx * cx));
}

half4 main(float2 coord) {
    float2 halfSize = max(size * 0.5, float2(1.0));
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = min(radiusAt(centeredCoord, cornerRadii), min(halfSize.x, halfSize.y));

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    float effRefractionHeight = max(refractionHeight * (1.0 + pressIntensity * 0.35), 1.0);
    float effRefractionAmount = refractionAmount * (1.0 + pressIntensity * 0.45);

    float2 cNorm = centeredCoord / max(length(centeredCoord), 0.001);
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 sdfGrad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
    float2 grad = normalize(sdfGrad + depthEffect * cNorm);

    half4 baseColor;
    if (-sd >= effRefractionHeight) {
        // Centro óptico da lente: deslocamento suave de profundidade
        float2 centerShift = cNorm * (pressIntensity * 3.5);
        baseColor = content.eval(coord + centerShift);
    } else {
        float clampedSd = min(sd, 0.0);
        float d = circleMap(1.0 - (-clampedSd / effRefractionHeight)) * effRefractionAmount;
        float2 refractedCoord = coord + d * grad;

        // Dispersão cromática de 7 comprimentos de onda (espectro completo iOS 27)
        float cornerFactor = (centeredCoord.x * centeredCoord.y) / max(halfSize.x * halfSize.y, 1.0);
        float disp = chromaticAberration * (0.45 + abs(cornerFactor) * 0.85) * (1.0 + pressIntensity * 0.5);
        float2 dispersedCoord = d * grad * disp * 0.14;

        half4 color = half4(0.0);

        half4 red = content.eval(refractedCoord + dispersedCoord);
        color.r += red.r / 3.5;
        color.a += red.a / 7.0;

        half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));
        color.r += orange.r / 3.5;
        color.g += orange.g / 7.0;
        color.a += orange.a / 7.0;

        half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));
        color.r += yellow.r / 3.5;
        color.g += yellow.g / 3.5;
        color.a += yellow.a / 7.0;

        half4 green = content.eval(refractedCoord);
        color.g += green.g / 3.5;
        color.a += green.a / 7.0;

        half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));
        color.g += cyan.g / 3.5;
        color.b += cyan.b / 3.0;
        color.a += cyan.a / 7.0;

        half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));
        color.b += blue.b / 3.0;
        color.a += blue.a / 7.0;

        half4 purple = content.eval(refractedCoord - dispersedCoord);
        color.r += purple.r / 7.0;
        color.b += purple.b / 3.0;
        color.a += purple.a / 7.0;

        baseColor = color;
    }

    // Mistura de tint translúcido da superfície de vidro
    baseColor.rgb = mix(baseColor.rgb, half3(tintColor.rgb), half(tintColor.a));

    // Dim óptico em tempo real (escurecimento dinâmico ao pressionar / ativar)
    float totalDim = clamp(dimAmount + pressIntensity * 0.08, 0.0, 0.88);
    baseColor.rgb *= half(1.0 - totalDim);

    return half4(baseColor.rgb, 1.0);
}
"""

@Stable
class LiquidBackdrop {
    val hazeState: HazeState = HazeState()
    internal val contentNode: RenderNode? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) RenderNode("liquid_backdrop_source") else null
    var sourcePositionInWindow: Offset by mutableStateOf(Offset.Zero)
    var sourceSize: Int by mutableIntStateOf(0)
    var frameTick: Long by mutableLongStateOf(0L)
    @Volatile var recordingSource: Boolean = false
}

@Composable
fun rememberLiquidBackdrop(): LiquidBackdrop = remember { LiquidBackdrop() }

val LocalLiquidBackdrop = compositionLocalOf<LiquidBackdrop?> { null }

class GlobalDimController {
    var activeCount by mutableIntStateOf(0)
    val isDimmed: Boolean get() = activeCount > 0
    fun push() { activeCount++ }
    fun pop() { activeCount = (activeCount - 1).coerceAtLeast(0) }
}

val LocalLiquidDim = compositionLocalOf { GlobalDimController() }

/**
 * Captura o conteúdo de fundo em tempo real tanto no RenderNode nativo (para o RuntimeShader AGSL)
 * quanto no HazeState da biblioteca dev.chrisbanes.haze.
 */
fun Modifier.liquidBackdropSource(backdrop: LiquidBackdrop): Modifier = this
    .haze(
        state = backdrop.hazeState,
        style = HazeStyle(
            tint = Color.Black.copy(alpha = 0.04f),
            blurRadius = 8.dp,
            noiseFactor = 0.02f
        )
    )
    .onGloballyPositioned { coords ->
        if (coords.isAttached) {
            backdrop.sourcePositionInWindow = coords.positionInWindow()
            backdrop.sourceSize = coords.size.width + coords.size.height
        }
    }
    .drawWithContent {
        val node = backdrop.contentNode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && node != null && drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
            val w = size.width.roundToInt().coerceAtLeast(1)
            val h = size.height.roundToInt().coerceAtLeast(1)
            node.setPosition(0, 0, w, h)
            val recording = node.beginRecording(w, h)
            val prevCanvas = drawContext.canvas
            val prevSize = drawContext.size
            drawContext.canvas = Canvas(recording)
            drawContext.size = size
            backdrop.recordingSource = true
            try {
                this@drawWithContent.drawContent()
            } finally {
                backdrop.recordingSource = false
                drawContext.canvas = prevCanvas
                drawContext.size = prevSize
                node.endRecording()
            }
            drawContext.canvas.nativeCanvas.drawRenderNode(node)
        } else {
            drawContent()
        }
    }

/**
 * Estado interativo em tempo real (toque, pressão, arrasto elástico e ângulo de luz especular)
 */
@Stable
class LiquidTouchState(private val scope: kotlinx.coroutines.CoroutineScope) {
    private val pressAnim = Animatable(0f)
    val pressProgress: Float get() = pressAnim.value
    var pointerOffset by mutableStateOf(Offset.Zero)
    var lightAngle by mutableFloatStateOf(-PI.toFloat() * 0.35f)

    val gestureModifier: Modifier = Modifier.pointerInput(scope) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val center = Offset(size.width / 2f, size.height / 2f)
            val delta0 = down.position - center
            if (delta0.getDistanceSquared() > 1f) {
                lightAngle = atan2(delta0.y, delta0.x)
            }
            scope.launch {
                pressAnim.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 380f))
            }
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: break
                    if (!change.pressed) break
                    val d = change.position - down.position
                    pointerOffset = d
                    val rel = change.position - center
                    if (rel.getDistanceSquared() > 4f) {
                        lightAngle = atan2(rel.y, rel.x)
                    }
                }
            } finally {
                pointerOffset = Offset.Zero
                scope.launch {
                    pressAnim.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = 320f))
                }
            }
        }
    }
}

@Composable
fun rememberLiquidTouchState(): LiquidTouchState {
    val scope = rememberCoroutineScope()
    return remember(scope) { LiquidTouchState(scope) }
}

/**
 * Modificador principal que aplica o motor óptico de Liquid Glass em tempo real:
 * - Refração por Lei de Snell via AGSL RuntimeShader (Android 13+)
 * - Blur + Saturação Vibrancy via RenderEffect nativo (Android 12+)
 * - Dim dinâmico em tempo real (ao tocar, segurar ou abrir opções)
 * - Deformação elástica em gel (tanh squash & stretch)
 */
@Composable
fun Modifier.drawLiquidGlass(
    backdrop: LiquidBackdrop? = LocalLiquidBackdrop.current,
    shape: Shape = CircleShape,
    refractionHeight: Dp = 10.dp,
    refractionAmount: Dp = 16.dp,
    blurRadius: Dp = 1.dp,
    chromaticAberration: Float = 0.4f,
    dim: Float = 0.04f,
    tint: Color = Color.White.copy(alpha = 0.03f),
    depthEffect: Boolean = true,
    isInteractive: Boolean = true,
    fallbackAlpha: Float = 0.18f,
    touchState: LiquidTouchState? = null
): Modifier {
    val activeTouch = touchState ?: if (isInteractive) rememberLiquidTouchState() else null
    val press = activeTouch?.pressProgress ?: 0f
    val animDim by animateFloatAsState(
        targetValue = (dim + press * 0.08f).coerceIn(0f, 0.85f),
        animationSpec = tween(140),
        label = "liquidDim"
    )
    val density = LocalDensity.current
    val insideSource = remember { BooleanArray(1) }
    var myWindowPos by remember { mutableStateOf(Offset.Zero) }
    val glassNode = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) RenderNode("liquid_glass_lens") else null
    }
    val runtimeShader = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { RuntimeShader(AGSL_LIQUID_GLASS_SHADER) }.getOrNull()
        } else null
    }

    return this
        .onGloballyPositioned { coords ->
            if (coords.isAttached) {
                myWindowPos = coords.positionInWindow()
            }
        }
        .then(if (activeTouch != null) activeTouch.gestureModifier else Modifier)
        .graphicsLayer {
            if (activeTouch != null && size.width > 0f && size.height > 0f) {
                val p = activeTouch.pressProgress
                val baseScale = 1f + (6.dp.toPx() / size.minDimension.coerceAtLeast(24f)) * p
                val maxOffset = size.minDimension.coerceAtLeast(1f)
                val off = activeTouch.pointerOffset
                translationX = maxOffset * tanh(0.055f * off.x / maxOffset)
                translationY = maxOffset * tanh(0.055f * off.y / maxOffset)
                val maxDragScale = 4.dp.toPx() / size.height.coerceAtLeast(1f)
                val angle = atan2(off.y, off.x)
                val aspectW = (size.width / size.height).coerceAtMost(1f)
                val aspectH = (size.height / size.width).coerceAtMost(1f)
                scaleX = baseScale + maxDragScale * abs(cos(angle) * off.x / size.maxDimension.coerceAtLeast(1f)) * aspectW
                scaleY = baseScale + maxDragScale * abs(sin(angle) * off.y / size.maxDimension.coerceAtLeast(1f)) * aspectH
            }
            clip = true
            this.shape = shape
        }
        .drawWithContent {
            val w = size.width.roundToInt().coerceAtLeast(1)
            val h = size.height.roundToInt().coerceAtLeast(1)
            val refH = with(density) { refractionHeight.toPx() }
            val refAmt = with(density) { -refractionAmount.toPx() }
            val blurPx = with(density) { blurRadius.toPx() }.coerceAtLeast(0.5f)
            val radii = extractCornerRadii(shape, size, density)
            val angle = activeTouch?.lightAngle ?: (-PI.toFloat() * 0.35f)
            val lightDirX = cos(angle)
            val lightDirY = sin(angle)

            // Se este vidro e desenhado DENTRO da gravacao do backdrop, ele nunca usa o RenderNode
            // (evita ciclo vidro -> backdrop -> vidro, que fecha o app).
            if (backdrop?.recordingSource == true) insideSource[0] = true

            val srcNode = backdrop?.contentNode
            val canRenderNode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    glassNode != null &&
                    srcNode != null &&
                    !insideSource[0] &&
                    srcNode.hasDisplayList() &&
                    drawContext.canvas.nativeCanvas.isHardwareAccelerated

            var drawnWithLens = false
            if (canRenderNode && glassNode != null && srcNode != null) {
                try {
                    val relX = myWindowPos.x - (backdrop?.sourcePositionInWindow?.x ?: 0f)
                    val relY = myWindowPos.y - (backdrop?.sourcePositionInWindow?.y ?: 0f)
                    glassNode.setPosition(0, 0, w, h)
                    val rec = glassNode.beginRecording(w, h)
                    try {
                        rec.translate(-relX, -relY)
                        rec.drawRenderNode(srcNode)
                    } finally {
                        glassNode.endRecording()
                    }

                    // 1) Vibrancy leve + Blur bem leve
                    val cm = ColorMatrix().apply { setSaturation(1.15f) }
                    val blurEff = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.MIRROR)
                    val vibrantBlur = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(cm), blurEff)

                    // 2) Refração Óptica AGSL em Tempo Real (Android 13+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && runtimeShader != null) {
                        runtimeShader.setFloatUniform("size", size.width, size.height)
                        runtimeShader.setFloatUniform("offset", 0f, 0f)
                        runtimeShader.setFloatUniform("cornerRadii", radii[0], radii[1], radii[2], radii[3])
                        runtimeShader.setFloatUniform("refractionHeight", refH)
                        runtimeShader.setFloatUniform("refractionAmount", refAmt)
                        runtimeShader.setFloatUniform("depthEffect", if (depthEffect) 1f else 0f)
                        runtimeShader.setFloatUniform("chromaticAberration", chromaticAberration)
                        runtimeShader.setFloatUniform("dimAmount", animDim)
                        runtimeShader.setFloatUniform("pressIntensity", press)
                        runtimeShader.setFloatUniform("tintColor", tint.red, tint.green, tint.blue, tint.alpha)

                        val shaderEff = RenderEffect.createRuntimeShaderEffect(runtimeShader, "content")
                        glassNode.setRenderEffect(RenderEffect.createChainEffect(shaderEff, vibrantBlur))
                    } else {
                        glassNode.setRenderEffect(vibrantBlur)
                    }

                    drawContext.canvas.nativeCanvas.drawRenderNode(glassNode)
                    drawnWithLens = true

                    // Android 12 (sem AGSL): aplica tint e dim por cima do RenderNode
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        drawRect(tint)
                        drawRect(Color.Black.copy(alpha = animDim))
                    }
                } catch (e: Throwable) {
                    drawnWithLens = false
                }
            }

            if (!drawnWithLens) {
                // Fallback seguro (sem refração): translúcido simples
                drawRect(Color(0xFF1E1E24).copy(alpha = fallbackAlpha))
                drawRect(tint)
                drawRect(Color.Black.copy(alpha = animDim))
            }

            // Conteúdo nítido acima da lente (ícones e textos)
            drawContent()
        }
}

private fun extractCornerRadii(shape: Shape, size: Size, density: androidx.compose.ui.unit.Density): FloatArray {
    val maxR = size.minDimension / 2f
    return when (shape) {
        CircleShape -> floatArrayOf(maxR, maxR, maxR, maxR)
        is CornerBasedShape -> {
            floatArrayOf(
                shape.topStart.toPx(size, density).coerceAtMost(maxR),
                shape.topEnd.toPx(size, density).coerceAtMost(maxR),
                shape.bottomEnd.toPx(size, density).coerceAtMost(maxR),
                shape.bottomStart.toPx(size, density).coerceAtMost(maxR)
            )
        }
        else -> floatArrayOf(maxR, maxR, maxR, maxR)
    }
}

// =====================================================================
// COMPONENTES PRONTOS DE LIQUID GLASS PARA O MUSICCLONE
// =====================================================================

/**
 * Botão circular de Liquid Glass (usado em Voltar, Play, Pular/Voltar música, Aleatório, Favoritar, Busca, 3 Pontinhos)
 */
@Composable
fun LiquidGlassCircle(
    size: Dp = 42.dp,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    tint: Color = Color.White.copy(alpha = 0.03f),
    dim: Float = 0.04f,
    refractionHeight: Dp = (size.value * 0.20f).dp,
    refractionAmount: Dp = (size.value * 0.32f).dp,
    content: @Composable BoxScope.() -> Unit
) {
    val touch = rememberLiquidTouchState()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(size)
            .drawLiquidGlass(
                shape = CircleShape,
                refractionHeight = refractionHeight,
                refractionAmount = refractionAmount,
                dim = dim,
                tint = tint,
                touchState = touch
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick
                    )
                } else Modifier
            ),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/**
 * Pílula/Cápsula de Liquid Glass (usado no botão Reproduzir, Mini-Player, Barra de Navegação e Próximos Shows)
 */
@Composable
fun LiquidGlassPill(
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    cornerRadius: Dp = 999.dp,
    tint: Color = Color.White.copy(alpha = 0.03f),
    dim: Float = 0.04f,
    refractionHeight: Dp = 10.dp,
    refractionAmount: Dp = 16.dp,
    horizontalPadding: Dp = 20.dp,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Center,
    content: @Composable RowScope.() -> Unit
) {
    val touch = rememberLiquidTouchState()
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(cornerRadius)
    Row(
        modifier = modifier
            .height(height)
            .drawLiquidGlass(
                shape = shape,
                refractionHeight = refractionHeight,
                refractionAmount = refractionAmount,
                dim = dim,
                tint = tint,
                touchState = touch
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick
                    )
                } else Modifier
            )
            .padding(horizontal = horizontalPadding),
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/**
 * Menu Suspenso (Dropdown) em Liquid Glass com Refração e Dim Global em Tempo Real
 */
@Composable
fun LiquidDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val dimCtrl = LocalLiquidDim.current
    DisposableEffect(expanded) {
        if (expanded) dimCtrl.push()
        onDispose { if (expanded) dimCtrl.pop() }
    }
    if (expanded) {
        Popup(
            alignment = Alignment.TopEnd,
            offset = IntOffset(0, 110),
            onDismissRequest = onDismissRequest,
            properties = PopupProperties(focusable = true)
        ) {
            val shape = RoundedCornerShape(22.dp)
            Column(
                modifier = modifier
                    .widthIn(min = 225.dp, max = 270.dp)
                    .drawLiquidGlass(
                        backdrop = null,
                        fallbackAlpha = 0.5f,
                        shape = shape,
                        refractionHeight = 14.dp,
                        refractionAmount = 22.dp,
                        blurRadius = 3.dp,
                        dim = 0.14f,
                        tint = Color.White.copy(alpha = 0.05f)
                    )
                    .padding(vertical = 8.dp),
                content = content
            )
        }
    }
}

@Composable
fun LiquidDropdownItem(
    text: String,
    icon: ImageVector,
    color: Color = Color.White,
    showDivider: Boolean = true,
    onClick: () -> Unit
) {
    val touch = rememberLiquidTouchState()
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(touch.gestureModifier)
                .background(Color.White.copy(alpha = touch.pressProgress * 0.14f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            T(text, 15, 600, color, Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp), tint = color)
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(0.6.dp)
                    .background(Color.White.copy(alpha = 0.14f))
            )
        }
    }
}
