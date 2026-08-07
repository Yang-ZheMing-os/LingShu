package com.lingshu.agent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle

// ==================== 核心配色常量（严格按需求） ====================

/** 主背景 #080C18（近乎纯黑） */
val PrimaryBackground = Color(0xFF080C18)

/** 主背景别名（ChatScreen等引用） */
val BackgroundPrimary = PrimaryBackground

/** 次级背景（卡片、顶栏等） */
val SecondaryBackground = Color(0xFF0D1324)

/** 次级背景别名 */
val BackgroundSecondary = SecondaryBackground

/** 三级背景（最浅的一层玻璃底） */
val TertiaryBackground = Color(0xFF121A2E)

/** 玻璃气泡背景 rgba(255,255,255,0.06) */
val GlassBubble = Color(0x0FFFFFFF)

/** 玻璃背景别名（通用组件用） */
val GlassBackground = GlassBubble

/** 玻璃气泡边框 rgba(255,255,255,0.08) */
val GlassBubbleBorder = Color(0x14FFFFFF)

/** 玻璃边框别名 */
val GlassBorder = GlassBubbleBorder

/** 强玻璃背景（用于更深的容器） */
val GlassBubbleStrong = Color(0x1FFFFFFF)

// ==================== 渐变气泡配色 ====================

/** 用户气泡冰蓝渐变起始 #4A8CFF */
val UserBubbleStart = Color(0xFF4A8CFF)

/** 用户气泡冰蓝渐变结束 #6E9CFF */
val UserBubbleEnd = Color(0xFF6E9CFF)

/** 冰蓝渐变扩展（用于Logo等场景） */
val IceBlueGradientStart = Color(0xFF66C4FF)
val IceBlueGradientMid = Color(0xFF4DA8FF)
val IceBlueGradientEnd = Color(0xFF3D8EFF)

/** AI气泡暖白渐变起始 #F5E6D3 */
val AiBubbleStart = Color(0xFFF5E6D3)

/** AI气泡暖白渐变结束 #FFFFFF */
val AiBubbleEnd = Color(0xFFFFFFFF)

/** 暖白渐变扩展（多层级） */
val WarmWhiteGradientStart = Color(0xFFFFFDF8)
val WarmWhiteGradientMid = Color(0xFFFBF7EF)
val WarmWhiteGradientEnd = Color(0xFFF3EDE0)

// ==================== 强调发光色 ====================

/** 强调发光色 #8AB4FF */
val AccentGlow = Color(0xFF8AB4FF)

/** 强调主色（与冰蓝渐变起始保持一致） */
val AccentPrimary = UserBubbleStart

/** 强调次级色 */
val AccentSecondary = UserBubbleEnd

// ==================== 文字颜色 ====================

/** 文字主色 #FFFFFF */
val TextPrimary = Color(0xFFFFFFFF)

/** 文字次级 rgba(255,255,255,0.6) */
val TextSecondary = Color(0x99FFFFFF)

/** 文字三级（hint、placeholder等） */
val TextTertiary = Color(0x66FFFFFF)

/** 禁用文字颜色 */
val TextDisabled = Color(0x33FFFFFF)

// ==================== 功能色 ====================

val Success = Color(0xFF4ADE80)
val Warning = Color(0xFFFBBF24)
val Error = Color(0xFFF87171)
val Info = Color(0xFF60A5FA)

// ==================== 状态Chip专用颜色 ====================

/** 监听中状态色（偏蓝绿） */
val StatusListening = Color(0xFF66C4FF)

/** 思考中状态色（偏紫） */
val StatusThinking = Color(0xFFA78BFA)

/** 执行中状态色（偏橙） */
val StatusExecuting = Color(0xFFFB923C)

/** 成功状态色 */
val StatusSuccess = Success

/** 警告状态色 */
val StatusWarning = Warning

/** 错误状态色 */
val StatusError = Error

// ==================== 健康数据专用配色 ====================

val HealthSteps = Color(0xFF4ADE80)
val HealthHeartRate = Color(0xFFF87171)
val HealthSleep = Color(0xFFA78BFA)
val HealthSpO2 = Color(0xFF60A5FA)
val HealthStress = Color(0xFFFB923C)

// ==================== Material3 ColorScheme ====================

private val DarkColorScheme = darkColorScheme(
    primary = AccentPrimary,
    onPrimary = TextPrimary,
    primaryContainer = GlassBubble,
    onPrimaryContainer = TextPrimary,
    secondary = AccentSecondary,
    onSecondary = TextPrimary,
    secondaryContainer = GlassBubble,
    onSecondaryContainer = TextPrimary,
    tertiary = AccentGlow,
    onTertiary = TextPrimary,
    tertiaryContainer = GlassBubbleStrong,
    onTertiaryContainer = TextPrimary,
    background = PrimaryBackground,
    onBackground = TextPrimary,
    surface = SecondaryBackground,
    onSurface = TextPrimary,
    surfaceVariant = TertiaryBackground,
    onSurfaceVariant = TextSecondary,
    error = Error,
    onError = TextPrimary,
    errorContainer = GlassBubble,
    onErrorContainer = Error,
    outline = GlassBubbleBorder,
    outlineVariant = Color(0x1FFFFFFF),
    scrim = Color(0x80000000)
)

private val LightColorScheme = lightColorScheme(
    primary = AccentPrimary,
    onPrimary = TextPrimary,
    primaryContainer = AccentGlow.copy(alpha = 0.12f),
    onPrimaryContainer = Color(0xFF1A2B4C),
    secondary = AccentSecondary,
    onSecondary = TextPrimary,
    secondaryContainer = AccentGlow.copy(alpha = 0.08f),
    onSecondaryContainer = Color(0xFF1A2B4C),
    tertiary = AccentGlow,
    onTertiary = TextPrimary,
    tertiaryContainer = AccentGlow.copy(alpha = 0.15f),
    onTertiaryContainer = Color(0xFF1A2B4C),
    background = Color(0xFFF8F9FC),
    onBackground = Color(0xFF0A0F1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0A0F1F),
    surfaceVariant = Color(0xFFF0F4FF),
    onSurfaceVariant = Color(0xFF4A5578),
    error = Error,
    onError = TextPrimary,
    errorContainer = Error.copy(alpha = 0.1f),
    onErrorContainer = Color(0xFF5C1F1F),
    outline = Color(0x1A000000),
    outlineVariant = Color(0x0F000000),
    scrim = Color(0x66000000)
)

// ==================== Typography 文字排版 ====================

private val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        color = TextPrimary,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle.Default
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        color = TextPrimary
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        color = TextPrimary
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        color = TextPrimary
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        color = TextPrimary
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        color = TextPrimary
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        color = TextPrimary
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        color = TextPrimary,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = TextPrimary,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        color = TextPrimary,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = TextSecondary,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = TextTertiary,
        letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = TextPrimary,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = TextSecondary,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        color = TextTertiary,
        letterSpacing = 0.5.sp
    )
)

// ==================== Shape 圆角配置 ====================

/**
 * 应用形状配置
 * - 小圆角：8dp（Chip、小按钮）
 * - 中圆角：16dp（普通卡片、输入框）
 * - 大圆角：24dp（玻璃气泡、大卡片）
 * - 玻璃气泡特殊圆角在 GradientBubble 中单独定义（16dp~24dp）
 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

// ==================== 自定义间距与玻璃尺寸（CompositionLocal） ====================

/**
 * 应用间距配置（通过CompositionLocal提供，统一各组件间距）
 */
@Immutable
data class AppSpacing(
    val xs: androidx.compose.ui.unit.Dp = 4.dp,
    val sm: androidx.compose.ui.unit.Dp = 8.dp,
    val md: androidx.compose.ui.unit.Dp = 12.dp,
    val lg: androidx.compose.ui.unit.Dp = 16.dp,
    val xl: androidx.compose.ui.unit.Dp = 24.dp,
    val xxl: androidx.compose.ui.unit.Dp = 32.dp
)

/**
 * 玻璃磨砂尺寸参数（圆角、边框宽度等）
 */
@Immutable
data class GlassDimensions(
    val cardCornerRadius: androidx.compose.ui.unit.Dp = 20.dp,
    val bubbleCornerRadius: androidx.compose.ui.unit.Dp = 24.dp,
    val inputCornerRadius: androidx.compose.ui.unit.Dp = 20.dp,
    val borderWidth: androidx.compose.ui.unit.Dp = 0.5.dp,
    val strongBorderWidth: androidx.compose.ui.unit.Dp = 1.dp,
    val glowElevation: androidx.compose.ui.unit.Dp = 8.dp,
    val strongGlowElevation: androidx.compose.ui.unit.Dp = 16.dp
)

val LocalAppSpacing = staticCompositionLocalOf { AppSpacing() }
val LocalGlassDimensions = staticCompositionLocalOf { GlassDimensions() }

/**
 * MaterialTheme扩展便捷访问：间距
 */
val MaterialTheme.spacing: AppSpacing
    @Composable
    @ReadOnlyComposable
    get() = LocalAppSpacing.current

/**
 * MaterialTheme扩展便捷访问：玻璃尺寸
 */
val MaterialTheme.glassDimensions: GlassDimensions
    @Composable
    @ReadOnlyComposable
    get() = LocalGlassDimensions.current

// ==================== GlassColors CompositionLocal ====================

/**
 * 玻璃磨砂专用颜色集合（通过CompositionLocal传递给子组件）
 */
@Immutable
class GlassColors(
    val glassBubble: Color,
    val glassBubbleStrong: Color,
    val glassBorder: Color,
    val accentGlow: Color,
    val background: Color
)

val LocalGlassColors = staticCompositionLocalOf<GlassColors> {
    error("GlassColors 未提供，请在根Composable中使用 GlassmorphismTheme 包裹内容")
}

// ==================== 主题入口 Composable ====================

/**
 * 玻璃磨砂主题入口
 *
 * 严格按需求配色方案：
 * - 默认强制暗色模式（darkTheme=true）
 * - 主背景 #080C18
 * - 玻璃气泡 rgba(255,255,255,0.06) / 边框 0.08
 * - 用户气泡冰蓝渐变 #4A8CFF → #6E9CFF
 * - AI气泡暖白渐变 #F5E6D3 → #FFFFFF
 * - 强调发光色 #8AB4FF
 *
 * @param darkTheme 是否暗色主题（默认true = 强制暗色）
 * @param dynamicColor 是否启用动态取色（Android 12+，默认false，保持品牌色）
 * @param content 主题包裹的Composable内容
 */
@Composable
fun GlassmorphismTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    // 按优先级决定ColorScheme：
    // 1. dynamicColor = true 时使用系统动态色（Android 12+）
    // 2. darkTheme 参数优先，其次 isSystemInDarkTheme()
    // 3. 默认始终使用 DarkColorScheme（需求：默认深色模式）
    val colorScheme = when {
        dynamicColor && darkTheme -> try {
            dynamicDarkColorScheme(context)
        } catch (_: Throwable) {
            DarkColorScheme
        }
        dynamicColor && !darkTheme -> try {
            dynamicLightColorScheme(context)
        } catch (_: Throwable) {
            LightColorScheme
        }
        // 核心逻辑：默认强制暗色模式
        darkTheme || !isSystemInDarkTheme() -> DarkColorScheme
        else -> LightColorScheme
    }

    // 通过CompositionLocal向子组件提供自定义间距/尺寸/玻璃配色
    CompositionLocalProvider(
        LocalAppSpacing provides AppSpacing(),
        LocalGlassDimensions provides GlassDimensions(),
        LocalGlassColors provides GlassColors(
            glassBubble = GlassBubble,
            glassBubbleStrong = GlassBubbleStrong,
            glassBorder = GlassBubbleBorder,
            accentGlow = AccentGlow,
            background = PrimaryBackground
        )
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
