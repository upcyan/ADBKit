// Adapted from compose-miuix-ui example (IosLiquidGlassNavigationBar) — Apache 2.0.

package com.lxithral.adbtools.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.launch
import com.lxithral.adbtools.ui.component.liquid.InnerShadow
import com.lxithral.adbtools.ui.component.liquid.innerShadow
import com.lxithral.adbtools.ui.component.liquid.lens
import com.lxithral.adbtools.ui.component.liquid.vibrancy
import com.lxithral.adbtools.ui.component.miuix.animation.DampedDragAnimation
import com.lxithral.adbtools.ui.component.miuix.animation.InteractiveHighlight
import com.lxithral.adbtools.ui.theme.isInDarkTheme
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.LightSource
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.sensor.rememberDeviceTilt
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

val LocalFloatingBottomBarTabScale = staticCompositionLocalOf { { 1f } }

/** 底栏左右内边距：胶囊与内容区共用，改这里就够 */
private val BarHorizontalPadding = 4.dp

/** 玻璃高度（底栏整体 64dp，上下各留 4dp） */
private val BarHeight = 56.dp

private val iosIndicatorSpecular: Highlight = Highlight(
    width = 1.dp,
    alpha = 1f,
    style = BloomStroke(
        color = Color.White.copy(alpha = 0.12f),
        innerBlurRadius = 2.0.dp,
        primaryLight = LightSource(
            position = LightPosition(0.5f, -0.3f, -0.05f),
            color = Color.White,
            intensity = 1f,
        ),
        secondaryLight = LightSource(
            position = LightPosition(0.5f, 0.8f, -0.5f),
            color = Color.White,
            intensity = 0.4f,
        ),
        dualPeak = true,
    ),
)

// Mirrors miuix-blur HighlightStyle's LIGHT_REF — keep in sync.
private const val LIGHT_REF_X = 0.5f
private const val LIGHT_REF_Y = 0.7f
private const val GRAVITY_DIR_THRESHOLD_SQ = 0.01f // |g_xy| > 0.1, ≈ 6° tilt
private const val GRAVITY_ANGLE_STEP_RAD = (3.0 * PI / 180.0).toFloat()

/** Tracks gravity for a `dualPeak` highlight's primary light, with an extra UV-clockwise offset on top. */
@Composable
private fun rememberQuantizedGravityAngle(): State<Float> {
    val tiltState = rememberDeviceTilt()
    return remember(tiltState) {
        derivedStateOf {
            val tilt = tiltState.value
            val magnitudeSquared = tilt.gravityX * tilt.gravityX + tilt.gravityY * tilt.gravityY
            if (magnitudeSquared > GRAVITY_DIR_THRESHOLD_SQ) {
                (atan2(tilt.gravityY, tilt.gravityX) / GRAVITY_ANGLE_STEP_RAD).roundToInt() * GRAVITY_ANGLE_STEP_RAD
            } else {
                (-PI / 2).toFloat()
            }
        }
    }
}

@Composable
private fun rememberGravityRotatedHighlight(
    base: Highlight,
    extraDegrees: Float = 0f,
): State<Highlight> {
    val baseStyle = base.style as BloomStroke
    val angle = rememberQuantizedGravityAngle()
    return remember(angle, base, extraDegrees) {
        derivedStateOf {
            val basePrimary = baseStyle.primaryLight
            val rad = angle.value + (extraDegrees * PI / 180.0).toFloat()
            base.copy(
                style = baseStyle.copy(
                    primaryLight = basePrimary.copy(
                        position = LightPosition(
                            x = LIGHT_REF_X + cos(rad),
                            y = LIGHT_REF_Y + sin(rad),
                            z = basePrimary.position.z,
                        ),
                    ),
                ),
            )
        }
    }
}

@Composable
fun RowScope.FloatingBottomBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalFloatingBottomBarTabScale.current
    val inheritedColor = LocalContentColor.current
    val contentColor = if (selected) MiuixTheme.colorScheme.primary else inheritedColor
    Column(
        modifier
            .semantics(mergeDescendants = true) {
                this.selected = selected
                role = Role.Tab
                onClick {
                    onClick()
                    true
                }
            }
            .onKeyEvent { event ->
                val activationKey = event.key == Key.Enter ||
                        event.key == Key.NumPadEnter || event.key == Key.Spacebar
                if (activationKey) {
                    if (event.type == KeyEventType.KeyUp) onClick()
                    true
                } else false
            }
            .focusable()
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val scale = scale()
                scaleX = scale
                scaleY = scale
            },
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

@Composable
fun FloatingBottomBar(
    modifier: Modifier = Modifier,
    selectedIndex: Int,
    onSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    isBlurEnabled: Boolean = true,
    tabMinWidth: Dp = 76.dp,
    content: @Composable RowScope.((Int) -> Unit) -> Unit
) {
    val isInDark = isInDarkTheme()
    val pillShape = remember { CircleShape }
    val tabContentColor = MiuixTheme.colorScheme.onSurface
    val surfaceContainer = MiuixTheme.colorScheme.surfaceContainer
    val containerColor = if (isBlurEnabled) surfaceContainer.copy(0.4f) else surfaceContainer

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()

    // 宽度必须由 tab 数量算出来，不能靠 IntrinsicSize.Min：
    // 带 weight(1f) 的 item 在 intrinsic 测量里不保证算上 defaultMinSize，
    // 之前由此导致 totalWidth 比真实内容窄 8dp，胶囊宽度 / 位置整体偏左。
    val barWidth = tabMinWidth * tabsCount + BarHorizontalPadding * 2
    val contentLeftPx = with(density) { BarHorizontalPadding.toPx() }

    var totalWidthPx by remember { mutableFloatStateOf(0f) }
    var contentWidthPx by remember { mutableFloatStateOf(0f) }
    var tabWidthPx by remember { mutableFloatStateOf(0f) }

    // 内容区（去掉左右 4dp padding）宽度 → 单个 tab 宽度，和 item 的实际布局严格一致
    LaunchedEffect(contentWidthPx, tabsCount) {
        tabWidthPx = if (contentWidthPx > 0f) contentWidthPx / tabsCount else 0f
    }

    val offsetAnimation = remember { Animatable(0f) }
    val rubberBandPx = with(density) { 4.dp.toPx() }
    val panelOffset by remember(rubberBandPx) {
        derivedStateOf {
            if (totalWidthPx == 0f) {
                0f
            } else {
                val fraction = (offsetAnimation.value / totalWidthPx).fastCoerceIn(-1f, 1f)
                rubberBandPx * fraction.sign * EaseOut.transform(abs(fraction))
            }
        }
    }

    var currentIndex by remember { mutableIntStateOf(selectedIndex) }
    val onSelectedUpdated by rememberUpdatedState(onSelected)

    fun indexAt(positionX: Float): Int {
        if (tabWidthPx == 0f) return currentIndex
        val logicalX = if (isLtr) positionX else totalWidthPx - positionX
        return ((logicalX - contentLeftPx) / tabWidthPx)
            .toInt()
            .coerceIn(0, tabsCount - 1)
    }

    val dampedDragAnimation = remember(animationScope, tabsCount, density, isLtr) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = selectedIndex.toFloat(),
            valueRange = 0f..(tabsCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            // 胶囊放大在原示例里是按 78/56 给整条底栏留余量用的；这里只有一个胶囊，
            // 1.39 倍会直接顶出底栏外，看起来就是"胶囊位置错乱"，所以收敛到轻微反馈。
            pressedScale = 1.06f,
            canDrag = { offset ->
                offset.x in 0f..totalWidthPx
            },
            onDragStarted = { position ->
                updateValue(indexAt(position.x).toFloat())
            },
            onDragStopped = {
                val targetIndex = targetValue.roundToInt().coerceIn(0, tabsCount - 1)
                if (currentIndex != targetIndex) {
                    currentIndex = targetIndex
                    onSelectedUpdated(targetIndex)
                }
                updateValue(targetIndex.toFloat())
                animationScope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
            },
            onDragCancelled = {
                updateValue(currentIndex.toFloat())
                animationScope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
            },
            onDrag = { _, dragAmount ->
                if (tabWidthPx > 0f && dragAmount.x != 0f) {
                    updateValue(
                        (targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                            .coerceIn(0f, (tabsCount - 1).toFloat()),
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            }
        )
    }

    LaunchedEffect(selectedIndex) {
        if (currentIndex != selectedIndex) {
            currentIndex = selectedIndex
            dampedDragAnimation.animateToValue(selectedIndex.toFloat())
        }
    }

    fun activateTab(index: Int) {
        if (index !in 0 until tabsCount) return
        if (currentIndex != index) {
            currentIndex = index
            onSelectedUpdated(index)
        }
        dampedDragAnimation.animateToValue(index.toFloat())
    }

    val interactiveHighlight = remember(animationScope, tabWidthPx, dampedDragAnimation) {
        InteractiveHighlight(
            animationScope = animationScope,
            position = { size, _ ->
                Offset(
                    if (isLtr) contentLeftPx + (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset
                    else size.width - contentLeftPx - (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset,
                    size.height / 2f
                )
            }
        )
    }

    val baseHighlight = rememberGravityRotatedHighlight(iosIndicatorSpecular, extraDegrees = -45f)

    Box(
        modifier = modifier
            .width(barWidth)
            .height(64.dp)
            .then(dampedDragAnimation.modifier)
            .graphicsLayer { translationX = panelOffset },
        contentAlignment = Alignment.CenterStart
    ) {
        // ① 底栏玻璃
        Box(
            Modifier
                .fillMaxWidth()
                .height(BarHeight)
                .onGloballyPositioned { coords ->
                    totalWidthPx = coords.size.width.toFloat()
                }
                .dropShadow(
                    shape = pillShape,
                    shadow = Shadow(
                        radius = 10.dp,
                        color = Color.Black,
                        alpha = if (isInDark) 0.2f else 0.1f,
                    ),
                )
                .then(
                    if (isBlurEnabled) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { pillShape },
                            effects = {
                                padding = maxOf(padding, 40.dp.toPx())
                                vibrancy()
                                blur(4.dp.toPx(), 4.dp.toPx())
                                lens(
                                    refractionHeight = 24.dp.toPx(),
                                    refractionAmount = 24.dp.toPx(),
                                )
                            },
                            highlight = { baseHighlight.value.copy(alpha = 0.75f) },
                            layerBlock = {
                                val width = size.width.coerceAtLeast(1f)
                                val s = lerp(1f, 1f + 16.dp.toPx() / width, dampedDragAnimation.pressProgress)
                                scaleX = s
                                scaleY = s
                            },
                            onDrawSurface = { drawRect(containerColor) },
                        )
                    } else {
                        Modifier.background(containerColor, pillShape)
                    }
                )
                .then(
                    if (isBlurEnabled) {
                        interactiveHighlight.modifier.then(interactiveHighlight.gestureModifier)
                    } else Modifier
                )
        )

        // ② 选中胶囊：画在玻璃之上、文字之下。
        //    之前是把整行文字（换成主色）采样进 tabsBackdrop 再用胶囊"透"出来，
        //    于是胶囊亮度完全取决于它底下压着的页面内容——主页那边压着亮色文字，胶囊就发灰发白，
        //    设置那边压着纯黑，胶囊就发暗，两个 tab 看起来不是一套样式。
        //    现在胶囊只负责底色，文字用正常文本绘制（清晰、恒定），两个 tab 完全一致。
        if (tabWidthPx > 0f) {
            val tabWidthDp = with(density) { tabWidthPx.toDp() }
            Box(
                Modifier
                    .graphicsLayer {
                        val progressOffset = contentLeftPx + dampedDragAnimation.value * tabWidthPx
                        translationX = if (isLtr) progressOffset else -progressOffset
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                    }
                    .clip(pillShape)
                    // 四边留同样的 4dp：之前胶囊和玻璃一样高（都 56dp），上下贴边、
                    // 左右却各留 4dp，看上去就是"边距没做恒定"。
                    .padding(vertical = BarHorizontalPadding)
                    // 胶囊必须"自发光"：底色接近不透明，否则它压着什么内容就长什么样
                    // ——主页底下有亮色文字、设置底下纯黑，两个选中态看起来完全不是一套。
                    .background(color = surfaceContainer.copy(alpha = 0.94f), shape = pillShape)
                    .background(
                        // 浅色下 surfaceContainer 本身接近白色，叠一点点黑才看得出胶囊；
                        // 深浅两套的"胶囊 vs 玻璃"对比度按像素对齐到同一量级。
                        color = if (isInDark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.09f),
                        shape = pillShape,
                    )
                    .innerShadow(shape = pillShape) {
                        InnerShadow(
                            radius = 8.dp * dampedDragAnimation.pressProgress,
                            color = Color.Black.copy(alpha = 0.15f),
                            alpha = dampedDragAnimation.pressProgress,
                        )
                    }
                    .height(BarHeight - BarHorizontalPadding * 2)
                    .width(tabWidthDp)
            )
        }

        // ③ 内容：选中项自己用主色，其余用 onSurface
        Row(
            Modifier
                .fillMaxHeight()
                .padding(horizontal = BarHorizontalPadding)
                // padding 之后量到的是真实内容宽度：胶囊宽度/位移都按它算，和 item 布局一一对应
                .onSizeChanged { contentWidthPx = it.width.toFloat() }
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides tabContentColor) {
                content(::activateTab)
            }
        }
    }
}
