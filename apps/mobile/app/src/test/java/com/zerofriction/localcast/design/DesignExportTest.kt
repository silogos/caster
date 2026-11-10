package com.zerofriction.localcast.design

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlendModeColorFilter
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.InspectableValue
import androidx.compose.ui.platform.isDebugInspectorInfoEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import com.zerofriction.localcast.adaptive.AdaptiveQualityController
import com.zerofriction.localcast.adaptive.QualityLevel
import com.zerofriction.localcast.audio.GameAudioState
import com.zerofriction.localcast.audio.MicState
import com.zerofriction.localcast.config.CastSettings
import com.zerofriction.localcast.config.DegradationStrategy
import com.zerofriction.localcast.config.QualityProfile
import com.zerofriction.localcast.config.toValues
import com.zerofriction.localcast.config.MicDeviceSource
import com.zerofriction.localcast.hotspot.HotspotMachine
import com.zerofriction.localcast.pairing.PairingClient
import com.zerofriction.localcast.pairing.PairingError
import com.zerofriction.localcast.service.AdaptiveUiState
import com.zerofriction.localcast.service.AudioRoutingUiState
import com.zerofriction.localcast.service.CastState
import com.zerofriction.localcast.service.ConnectedDesktop
import com.zerofriction.localcast.service.MicRoutingUiState
import com.zerofriction.localcast.service.VcCapability
import com.zerofriction.localcast.service.VirtualCameraUiState
import com.zerofriction.localcast.signaling.Envelope
import com.zerofriction.localcast.thermal.ThermalState
import com.zerofriction.localcast.thermal.ThermalStatus
import com.zerofriction.localcast.ui.home.HomeContent
import com.zerofriction.localcast.ui.home.MicPermission
import com.zerofriction.localcast.ui.pairing.ScanContent
import com.zerofriction.localcast.ui.profiles.ProfileEditContent
import com.zerofriction.localcast.ui.theme.LocalCastTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Measures the shipped UI for the design file (designs/mobile-app.figcraft):
 * every screen state is composed at a 412dp phone width with native text
 * metrics, then its layout tree is exported as JSON — every layout node
 * with its outer/inner box, Row/Column/Box policy (arrangement, alignment),
 * size modifiers, and what it draws (backgrounds, borders, clip shapes,
 * text, icon paths, drawBehind drawings) — which tools/design/ rebuilds as
 * nested auto-layout frames; plus a PNG of the real render to compare. Not a test of
 * behavior: it runs only when DESIGN_EXPORT_DIR is set
 * (tools/design/README.md), so CI skips it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h2400dp-xxhdpi")
class DesignExportTest {

    @get:Rule
    val compose = createComposeRule()

    private val outDir = System.getenv("DESIGN_EXPORT_DIR")?.let(::File)

    @Before
    fun onlyWhenAsked() {
        assumeTrue("set DESIGN_EXPORT_DIR to export the design", outDir != null)
        isDebugInspectorInfoEnabled = true
    }

    private val castingSince = System.currentTimeMillis() - 754_000L
    private val balanced = QualityLevel.PRESETS[1]
    private val cool = QualityLevel.PRESETS[0]

    /** Home with two profiles of the user's own (ADR-024), the first one selected for editing's sake. */
    private val withOwnProfiles = CastSettings.default()
        .withProfileAdded("Stream 60", QualityProfile.SHARP.toValues().copy(fps = 60, degradationStrategy = DegradationStrategy.MAINTAIN_FRAMERATE).withBitrateCeiling(12_000_000))
        .withProfileAdded("Couch", QualityProfile.SHARP.toValues().copy(autoQuality = false))

    /** Scrolling screens: a tall window, the frame as tall as the content. */
    @Test
    fun exportScrollingScreens() = export(scrollingScreens(), fixedHeight = null)

    /** Full-screen layouts (centered content): a real phone height, the frame is the screen. */
    @Test
    @Config(qualifiers = "w412dp-h915dp-xxhdpi")
    fun exportFullScreens() = export(fullScreens(), fixedHeight = PHONE_HEIGHT_DP)

    private fun fullScreens(): List<Pair<String, @Composable () -> Unit>> = listOf(
        "10 Scan · Camera permission" to { ScanContent(PairingClient.State.Idle, {}, {}) },
        "11 Scan · Pairing" to { ScanContent(PairingClient.State.Connecting, {}, {}) },
        "12 Scan · Failed" to { ScanContent(PairingClient.State.Failed(PairingError.EXPIRED), {}, {}) },
    )

    private fun scrollingScreens(): List<Pair<String, @Composable () -> Unit>> = listOf(
            "01 Home · Standby" to { home(CastState.Idle, settings = withOwnProfiles) },
            "02 Home · Starting" to { home(CastState.Starting("Gaming PC")) },
            "03 Home · On air" to {
                home(
                    CastState.Casting("Gaming PC", castingSince),
                    gameAudio = GameAudioState.Active,
                    mic = MicState.Active,
                    micRouting = MicRoutingUiState(MicDeviceSource.BUILTIN),
                    thermal = ThermalState(status = ThermalStatus.LIGHT, headroom = 0.62f, batteryTempC = 34.5f),
                    adaptive = AdaptiveUiState(current = balanced, ceiling = balanced),
                    live = balanced,
                    settings = CastSettings.default().copy(mic = true),
                    vc = VirtualCameraUiState(VcCapability.Ready, active = false),
                    routing = AudioRoutingUiState(Envelope.AUDIO_ROUTING_SPEAKERS, cableAvailable = true, known = true),
                )
            },
            "04 Home · On air, auto quality stepped down" to {
                home(
                    CastState.Casting("Gaming PC", castingSince),
                    gameAudio = GameAudioState.Active,
                    thermal = ThermalState(status = ThermalStatus.SEVERE, headroom = 0.95f, batteryTempC = 41.0f),
                    adaptive = AdaptiveUiState(
                        current = cool,
                        ceiling = balanced,
                        lastChange = AdaptiveQualityController.QualityChange(
                            balanced, cool, AdaptiveQualityController.Direction.DOWN, AdaptiveQualityController.Reason.THERMAL,
                        ),
                    ),
                    live = cool,
                )
            },
            "05 Home · Fault" to {
                home(CastState.Failed("Couldn't connect to the desktop. Make sure both devices are on the same Wi-Fi network."))
            },
            "06 Home · Saved desktop (offline)" to {
                home(CastState.Idle, connection = ConnectedDesktop.State.Saved("Gaming PC"))
            },
            "07 Home · Reconnect failed" to {
                home(
                    CastState.Idle,
                    connection = ConnectedDesktop.State.Saved("Gaming PC"),
                    pairing = PairingClient.State.Failed(PairingError.CONNECT_UNREACHABLE),
                    hotspot = HotspotMachine.State.Active("DIRECT-zfc-4821", "cast-7731"),
                )
            },
            "08 Home · Mic needs permission" to {
                home(
                    CastState.Casting("Gaming PC", castingSince),
                    gameAudio = GameAudioState.Active,
                    mic = MicState.NeedsPermission,
                    micPermission = MicPermission.Blocked,
                    live = balanced,
                    settings = CastSettings.default().copy(mic = true),
                )
            },
            "09 Profile · Edit" to {
                ProfileEditContent(
                    profile = withOwnProfiles.customProfiles.first(),
                    selected = true,
                    canDuplicate = true,
                    onRename = {},
                    onEdit = {},
                    onDuplicate = {},
                    onDelete = {},
                )
            },
        )

    private fun export(screens: List<Pair<String, @Composable () -> Unit>>, fixedHeight: Int?) {
        var current by mutableStateOf<(@Composable () -> Unit)?>(null)
        var root: android.view.View? = null
        compose.setContent {
            root = androidx.compose.ui.platform.LocalView.current
            LocalCastTheme { current?.invoke() }
        }
        for ((name, content) in screens) {
            current = content
            compose.waitForIdle()
            val file = name.substringBefore(" ")
            val json = exportTree(name)
            if (fixedHeight != null) json.put("height", fixedHeight).put("fixedHeight", true)
            File(outDir, "$file.json").writeText(json.toString(1))
            // Software draw of the real view (captureToImage waits for a
            // hardware redraw Robolectric never delivers).
            val view = root!!
            val height = minOf(view.height, (json.getInt("height") * DENSITY).toInt())
            val cropped = Bitmap.createBitmap(view.width, height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(cropped))
            File(outDir, "$file.png").outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Composable
    private fun home(
        cast: CastState,
        connection: ConnectedDesktop.State = ConnectedDesktop.State.Connected("Gaming PC"),
        pairing: PairingClient.State = PairingClient.State.Idle,
        gameAudio: GameAudioState = GameAudioState.Off,
        mic: MicState = MicState.Off,
        micPermission: MicPermission = MicPermission.Granted,
        micRouting: MicRoutingUiState? = null,
        thermal: ThermalState = ThermalState(),
        adaptive: AdaptiveUiState = AdaptiveUiState(),
        live: QualityLevel? = null,
        settings: CastSettings = CastSettings.default(),
        vc: VirtualCameraUiState = VirtualCameraUiState(),
        routing: AudioRoutingUiState = AudioRoutingUiState(),
        hotspot: HotspotMachine.State = HotspotMachine.State.Idle,
    ) = HomeContent(
        castState = cast,
        connection = connection,
        pairingState = pairing,
        gameAudioState = gameAudio,
        micState = mic,
        micPermission = micPermission,
        thermalState = thermal,
        adaptiveState = adaptive,
        micRouting = micRouting,
        settings = settings,
        liveQuality = live,
        vcState = vc,
        audioRouting = routing,
        hotspotState = hotspot,
    )

    // ---- the layout-tree walk ----

    /** Where the modifier handlers below put what they find: the node being walked. */
    private var items = JSONArray()
    private var maxBottom = 0f

    private fun exportTree(name: String): JSONObject {
        maxBottom = 0f
        val root = walk(compose.onRoot().fetchSemanticsNode().layoutInfo, parentPolicy = "box")
        return JSONObject()
            .put("name", name)
            .put("width", WIDTH_DP)
            .put("height", (maxBottom + BOTTOM_MARGIN_DP).toInt())
            .put("root", root)
    }

    /**
     * One layout node → one JSON node: its outer box (before its own
     * padding) and inner box (where children are placed), how it lays out
     * its children (Row/Column arrangement + alignment, Box alignment), how
     * it is sized (fill / fixed / hug, min sizes), what it draws (items) and
     * its children — the tree the design file rebuilds as auto layout.
     */
    private fun walk(node: LayoutInfo, parentPolicy: String): JSONObject? {
        if (!node.isPlaced) return null
        val outer = bounds(coordinator(node, "getOuterCoordinator"))
        val inner = bounds(coordinator(node, "getInnerCoordinator"))
        val json = JSONObject().put("outer", rectJson(outer)).put("inner", rectJson(inner))
        describePolicy(node, json)
        json.put("sizing", sizing(node, parentPolicy, json))
        val own = JSONArray()
        items = own
        // Modifier.clip(shape) is a clipping graphicsLayer: a following plain
        // background (LedDot, icon wells) takes the clip's shape.
        var clip: Shape? = null
        for (info in node.getModifierInfo()) {
            val bounds = bounds(info.coordinates)
            if (bounds.width <= 0f && bounds.height <= 0f) continue
            val modifier = info.modifier
            val inspect = (modifier as? InspectableValue)
            val elements = inspect?.inspectableElements?.associate { it.name to it.value }.orEmpty()
            if (inspect?.nameFallback == "graphicsLayer" && elements["clip"] == true) {
                (elements["shape"] as? Shape)?.takeIf { it != androidx.compose.ui.graphics.RectangleShape }?.let { clip = it }
            }
            when {
                inspect?.nameFallback == "background" -> background(
                    bounds,
                    if (elements["shape"] == androidx.compose.ui.graphics.RectangleShape && clip != null) elements + ("shape" to clip) else elements,
                )
                inspect?.nameFallback == "border" -> border(bounds, elements)
                inspect?.nameFallback == "paint" -> icon(bounds, elements)
                modifier.javaClass.simpleName == "TextStringSimpleElement" ||
                    modifier.javaClass.simpleName == "TextAnnotatedStringElement" -> text(bounds, modifier)
                modifier.javaClass.simpleName == "DrawBehindElement" -> drawing(bounds, modifier)
            }
        }
        json.put("items", own)
        maxBottom = maxOf(maxBottom, outer.bottom)
        val kids = JSONArray()
        val policy = json.optString("policy")
        for (child in children(node)) walk(child, policy)?.let { kids.put(it) }
        json.put("children", kids)
        return json
    }

    private fun coordinator(node: LayoutInfo, prefix: String): LayoutCoordinates =
        node.javaClass.methods.first { it.name.startsWith(prefix) && it.parameterCount == 0 }.invoke(node) as LayoutCoordinates

    private fun rectJson(r: Rect) = JSONArray().put(r.left).put(r.top).put(r.width).put(r.height)

    /** Row / Column / Box / Spacer / Text from the node's measure policy, with its arrangement and alignment. */
    private fun describePolicy(node: LayoutInfo, json: JSONObject) {
        val policy = node.javaClass.methods.first { it.name == "getMeasurePolicy" }.invoke(node)
        val name = policy.javaClass.simpleName
        fun bias(value: Any?): Float? = Regex("bias=(-?[0-9.]+)").find(value.toString())?.groupValues?.get(1)?.toFloat()
        fun align(b: Float?) = when { b == null -> "start"; b < -0.5f -> "start"; b > 0.5f -> "end"; else -> "center" }
        fun arrangement(value: Any?): JSONObject {
            val text = value.toString()
            val spacing = Regex("spacedAligned\\(([0-9.]+)\\.dp").find(text)?.groupValues?.get(1)?.toFloat() ?: 0f
            val kind = when {
                text.contains("SpaceBetween") -> "spaceBetween"
                text.contains("SpaceAround") -> "spaceAround"
                text.contains("SpaceEvenly") -> "spaceEvenly"
                text.contains("spacedAligned") -> if (text.contains("Center")) "center" else if (text.contains("End") || text.contains("Bottom")) "end" else "start"
                text.contains("Center") -> "center"
                text.contains("End") || text.contains("Bottom") -> "end"
                else -> "start"
            }
            return JSONObject().put("kind", kind).put("spacing", spacing)
        }
        when {
            name == "RowMeasurePolicy" -> json.put("policy", "row")
                .put("arrangement", arrangement(field(policy, "horizontalArrangement")))
                .put("cross", align(bias(field(policy, "verticalAlignment"))))
            name == "ColumnMeasurePolicy" -> json.put("policy", "column")
                .put("arrangement", arrangement(field(policy, "verticalArrangement")))
                .put("cross", align(bias(field(policy, "horizontalAlignment"))))
            name == "SpacerMeasurePolicy" -> json.put("policy", "spacer")
            name == "EmptyMeasurePolicy" && policy.javaClass.name.contains("text") -> json.put("policy", "text")
            name == "BoxMeasurePolicy" -> {
                val a = field(policy, "alignment").toString()
                fun b(key: String) = Regex("$key=(-?[0-9.]+)").find(a)?.groupValues?.get(1)?.toFloat()
                json.put("policy", "box").put("boxH", align(b("horizontalBias"))).put("boxV", align(b("verticalBias")))
                    .put("stretch", field(policy, "propagateMinConstraints") == true)
            }
            name.contains("EmptyBoxMeasurePolicy") || name == "RootMeasurePolicy" -> json.put("policy", "box")
            else -> json.put("policy", "other").put("policyClass", name)
        }
    }

    /**
     * fill / fixed / hug per axis, from the node's size modifiers:
     * fillMax* (and weight on the parent's main axis) → fill; size / width /
     * height → fixed; anything else hugs its content. Min sizes ride along.
     */
    private fun sizing(node: LayoutInfo, parentPolicy: String, json: JSONObject): JSONObject {
        var h = "hug"
        var v = "hug"
        var minW: Float? = null
        var minH: Float? = null
        var selfAlign: Float? = null
        for (info in node.getModifierInfo()) {
            val inspect = info.modifier as? InspectableValue ?: continue
            val e = inspect.inspectableElements.associate { it.name to it.value }
            fun dp(key: String) = (e[key] as? Dp)?.value?.takeIf { !it.isNaN() }
            when (inspect.nameFallback) {
                "fillMaxWidth" -> if (h == "hug") h = "fill"
                "fillMaxHeight" -> if (v == "hug") v = "fill"
                "fillMaxSize" -> { if (h == "hug") h = "fill"; if (v == "hug") v = "fill" }
                "weight" -> if (parentPolicy == "row") h = "fill" else if (parentPolicy == "column") v = "fill"
                "size", "requiredSize" -> { h = "fixed"; v = "fixed" }
                "width", "requiredWidth" -> h = "fixed"
                "height", "requiredHeight" -> v = "fixed"
                "heightIn" -> minH = dp("min") ?: minH
                "widthIn" -> minW = dp("min") ?: minW
                "defaultMinSize" -> { minW = dp("minWidth") ?: minW; minH = dp("minHeight") ?: minH }
                // RowScope/ColumnScope.align: this child's own cross alignment.
                "align" -> selfAlign = Regex("bias=(-?[0-9.]+)").find((inspect.valueOverride ?: e.values.firstOrNull()).toString())?.groupValues?.get(1)?.toFloat()
            }
        }
        if (json.optString("policy") == "spacer") { h = "fixed"; v = "fixed" }
        val align = selfAlign?.let { if (it < -0.5f) "start" else if (it > 0.5f) "end" else "center" }
        return JSONObject().put("h", h).put("v", v).put("minW", minW ?: JSONObject.NULL).put("minH", minH ?: JSONObject.NULL)
            .put("selfAlign", align ?: JSONObject.NULL)
    }

    /** LayoutNode's children (internal API — the exporter is a dev tool, not app code). */
    @Suppress("UNCHECKED_CAST")
    private fun children(node: LayoutInfo): List<LayoutInfo> {
        val getter = node.javaClass.methods.firstOrNull { it.name.startsWith("getChildren") && it.parameterCount == 0 }
            ?: return emptyList()
        return getter.invoke(node) as? List<LayoutInfo> ?: emptyList()
    }

    private fun bounds(coordinates: LayoutCoordinates): Rect {
        val p = coordinates.positionInRoot()
        return Rect(p.x / DENSITY, p.y / DENSITY, (p.x + coordinates.size.width) / DENSITY, (p.y + coordinates.size.height) / DENSITY)
    }

    private fun box(kind: String, b: Rect): JSONObject {
        maxBottom = maxOf(maxBottom, b.bottom)
        return JSONObject().put("kind", kind).put("x", b.left).put("y", b.top).put("w", b.width).put("h", b.height)
    }

    private fun color(c: Color): JSONArray = JSONArray().put(c.red).put(c.green).put(c.blue).put(c.alpha)

    private fun radii(shape: Any?, b: Rect): JSONArray {
        val s = shape as? Shape ?: return JSONArray().put(0).put(0).put(0).put(0)
        return when (val outline = s.createOutline(Size(b.width, b.height), LayoutDirection.Ltr, Density(1f))) {
            is Outline.Rounded -> outline.roundRect.let {
                JSONArray().put(it.topLeftCornerRadius.x).put(it.topRightCornerRadius.x)
                    .put(it.bottomRightCornerRadius.x).put(it.bottomLeftCornerRadius.x)
            }
            else -> JSONArray().put(0).put(0).put(0).put(0)
        }
    }

    private fun background(b: Rect, e: Map<String, Any?>) {
        val c = (e["color"] as? Color) ?: ((e["brush"] as? SolidColor)?.value) ?: return
        if (c.alpha == 0f) return
        val alpha = (e["alpha"] as? Float) ?: 1f
        items.put(box("rect", b).put("fill", color(c.copy(alpha = c.alpha * alpha))).put("radii", radii(e["shape"], b)))
    }

    private fun border(b: Rect, e: Map<String, Any?>) {
        val c = (e["color"] as? Color) ?: ((e["brush"] as? SolidColor)?.value) ?: return
        val width = (e["width"] as? Dp)?.value ?: 1f
        items.put(box("rect", b).put("stroke", color(c)).put("strokeWidth", width).put("radii", radii(e["shape"], b)))
    }

    /** A (possibly private) field — or a `by mutableStateOf` property's state value. */
    private fun field(target: Any, name: String): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            cls.declaredFields.firstOrNull { it.name == name }?.let { it.isAccessible = true; return it.get(target) }
            cls.declaredFields.firstOrNull { it.name == "$name\$delegate" }?.let {
                it.isAccessible = true
                return (it.get(target) as? androidx.compose.runtime.State<*>)?.value
            }
            cls = cls.superclass
        }
        return null
    }

    private fun text(b: Rect, element: Any) {
        val raw = field(element, "text")
        val string = (raw as? AnnotatedString)?.text ?: raw as? String ?: return
        val style = field(element, "style") as? TextStyle ?: TextStyle.Default
        val produced = (field(element, "color") as? ColorProducer)?.invoke()
        val c = produced?.takeIf { it.isSpecified() } ?: style.color.takeIf { it.isSpecified() } ?: Color.White
        fun TextUnit.orNull() = if (isSpecified()) value else null
        items.put(
            box("text", b).put("text", string).put("color", color(c))
                .put("size", style.fontSize.orNull() ?: 14f)
                .put("weight", style.fontWeight?.weight ?: 400)
                .put("letterSpacing", style.letterSpacing.orNull() ?: 0f)
                .put("lineHeight", style.lineHeight.orNull() ?: JSONObject.NULL)
                .put("align", when (style.textAlign) { TextAlign.Center -> "center"; TextAlign.End, TextAlign.Right -> "right"; else -> "left" })
                .put("mono", style.fontFamily == FontFamily.Monospace),
        )
    }

    private fun Color.isSpecified() = this != Color.Unspecified
    private fun TextUnit.isSpecified() = isSp || isEm

    /**
     * An icon: VectorPainter draws through a cached bitmap, so its paths are
     * read from the vector tree itself (VectorComponent → groups → paths),
     * scaled from the viewport to the drawn box; the paint's tint wins.
     */
    private fun icon(b: Rect, e: Map<String, Any?>) {
        val painter = e["painter"] as? VectorPainter ?: return
        val tint = (e["colorFilter"] as? BlendModeColorFilter)?.color
        val vector = field(painter, "vector") ?: return
        val viewport = field(vector, "viewportSize") as? Size ?: return
        val root = field(vector, "root") ?: return
        val scale = minOf(b.width / viewport.width, b.height / viewport.height)
        val paths = JSONArray()
        fun visit(group: Any) {
            @Suppress("UNCHECKED_CAST")
            val kids = field(group, "children") as? List<Any> ?: return
            for (kid in kids) {
                if (kid.javaClass.simpleName == "GroupComponent") { visit(kid); continue }
                @Suppress("UNCHECKED_CAST")
                val nodes = field(kid, "pathData") as? List<PathNode> ?: continue
                val strokeBrush = field(kid, "stroke") as? SolidColor
                val fillBrush = field(kid, "fill") as? SolidColor
                val strokeWidth = (field(kid, "strokeLineWidth") as? Float) ?: 0f
                val segs = PathNodeSegments(scale).convert(nodes)
                val stroked = strokeBrush != null && strokeWidth > 0f
                val c = tint ?: (if (stroked) strokeBrush!!.value else fillBrush?.value) ?: Color.White
                paths.put(
                    JSONObject().put("segments", segs).put("color", color(c))
                        .put("stroke", stroked).put("fill", fillBrush != null)
                        .put("strokeWidth", strokeWidth * scale),
                )
            }
        }
        visit(root)
        if (paths.length() > 0) items.put(box("vector", b).put("paths", paths))
    }

    /** A drawn path as a polyline (Skia's approximation — the path iterator needs a native lib Robolectric lacks), in dp. */
    private fun segments(path: Path, scale: Float, dx: Float, dy: Float): JSONArray {
        val segs = JSONArray()
        val pts = path.asAndroidPath().approximate(0.5f)
        var prev: Pair<Float, Float>? = null
        var i = 0
        while (i + 2 < pts.size) {
            val x = pts[i + 1] * scale + dx
            val y = pts[i + 2] * scale + dy
            val p0 = prev
            segs.put(
                if (p0 == null) JSONObject().put("type", "Move").put("points", JSONArray().put(x).put(y))
                else JSONObject().put("type", "Line").put("points", JSONArray().put(p0.first).put(p0.second).put(x).put(y)),
            )
            prev = x to y
            i += 3
        }
        return segs
    }

    private fun drawing(b: Rect, element: Any) {
        @Suppress("UNCHECKED_CAST")
        val onDraw = field(element, "onDraw") as? (DrawScope.() -> Unit) ?: return
        val recorder = Recorder()
        try {
            CanvasDrawScope().draw(Density(DENSITY), LayoutDirection.Ltr, recorder, Size(b.width * DENSITY, b.height * DENSITY)) { onDraw() }
        } catch (_: Throwable) {
            return
        }
        recorder.ops.forEach { op -> items.put(op.put("ox", b.left).put("oy", b.top)) }
        recorder.paths.forEach { (path, paint) ->
            val stroked = paint.style == PaintingStyle.Stroke
            items.put(
                box("vector", b).put(
                    "paths",
                    JSONArray().put(
                        JSONObject().put("segments", segments(path, 1f / DENSITY, 0f, 0f)).put("color", color(paint.color))
                            .put("stroke", stroked).put("fill", !stroked).put("strokeWidth", paint.strokeWidth / DENSITY),
                    ),
                ),
            )
        }
        maxBottom = maxOf(maxBottom, b.bottom)
    }

    /** A canvas that records what a draw block paints (lines, circles, rects, paths), in dp. */
    private inner class Recorder(base: Canvas = Canvas(ImageBitmap(1, 1))) : Canvas by base {
        val ops = mutableListOf<JSONObject>()
        val paths = mutableListOf<Pair<Path, Paint>>()
        private var tx = 0f
        private var ty = 0f
        private val stack = ArrayDeque<Pair<Float, Float>>()

        override fun save() { stack.addLast(tx to ty) }
        override fun restore() { stack.removeLastOrNull()?.let { (x, y) -> tx = x; ty = y } }
        override fun translate(dx: Float, dy: Float) { tx += dx; ty += dy }

        private fun paintJson(o: JSONObject, paint: Paint): JSONObject = o
            .put("color", color(paint.color.copy(alpha = paint.color.alpha * paint.alpha)))
            .put("stroke", paint.style == PaintingStyle.Stroke)
            .put("strokeWidth", paint.strokeWidth / DENSITY)
            .put("clear", paint.blendMode == BlendMode.Clear)

        override fun drawLine(p1: Offset, p2: Offset, paint: Paint) {
            ops += paintJson(
                JSONObject().put("kind", "line").put("x1", (p1.x + tx) / DENSITY).put("y1", (p1.y + ty) / DENSITY)
                    .put("x2", (p2.x + tx) / DENSITY).put("y2", (p2.y + ty) / DENSITY),
                paint,
            )
        }

        override fun drawCircle(center: Offset, radius: Float, paint: Paint) {
            ops += paintJson(
                JSONObject().put("kind", "circle").put("cx", (center.x + tx) / DENSITY).put("cy", (center.y + ty) / DENSITY)
                    .put("r", radius / DENSITY),
                paint,
            )
        }

        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            ops += paintJson(
                JSONObject().put("kind", "drect").put("x", (left + tx) / DENSITY).put("y", (top + ty) / DENSITY)
                    .put("w", (right - left) / DENSITY).put("h", (bottom - top) / DENSITY).put("r", 0),
                paint,
            )
        }

        override fun drawRoundRect(left: Float, top: Float, right: Float, bottom: Float, radiusX: Float, radiusY: Float, paint: Paint) {
            ops += paintJson(
                JSONObject().put("kind", "drect").put("x", (left + tx) / DENSITY).put("y", (top + ty) / DENSITY)
                    .put("w", (right - left) / DENSITY).put("h", (bottom - top) / DENSITY).put("r", radiusX / DENSITY),
                paint,
            )
        }

        override fun drawArc(left: Float, top: Float, right: Float, bottom: Float, startAngle: Float, sweepAngle: Float, useCenter: Boolean, paint: Paint) {
            ops += paintJson(
                JSONObject().put("kind", "arc").put("x", (left + tx) / DENSITY).put("y", (top + ty) / DENSITY)
                    .put("w", (right - left) / DENSITY).put("h", (bottom - top) / DENSITY)
                    .put("start", startAngle).put("sweep", sweepAngle),
                paint,
            )
        }

        override fun drawPath(path: Path, paint: Paint) {
            val copy = Path().apply { addPath(path, Offset(tx, ty)) }
            paths += copy to Paint().also {
                it.color = paint.color.copy(alpha = paint.color.alpha * paint.alpha)
                it.style = paint.style
                it.strokeWidth = paint.strokeWidth
            }
        }
    }

    /**
     * Vector-drawable path data → absolute Move/Line/Cubic/Close segments,
     * scaled to dp (arcs become cubics — the SVG endpoint→center method).
     */
    private class PathNodeSegments(private val scale: Float) {
        private val out = JSONArray()
        private var cx = 0f; private var cy = 0f
        private var sx = 0f; private var sy = 0f
        private var lastCtrlX = 0f; private var lastCtrlY = 0f
        private var lastWasCurve = false; private var lastWasQuad = false

        private fun pt(vararg xy: Float) = JSONArray().also { a -> xy.forEach { a.put(it * scale) } }
        private fun move(x: Float, y: Float) { out.put(JSONObject().put("type", "Move").put("points", pt(x, y))); cx = x; cy = y; sx = x; sy = y }
        private fun line(x: Float, y: Float) { out.put(JSONObject().put("type", "Line").put("points", pt(cx, cy, x, y))); cx = x; cy = y }
        private fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) {
            out.put(JSONObject().put("type", "Cubic").put("points", pt(cx, cy, x1, y1, x2, y2, x, y)))
            lastCtrlX = x2; lastCtrlY = y2; cx = x; cy = y
        }
        private fun quad(qx: Float, qy: Float, x: Float, y: Float) {
            cubic(cx + 2f / 3 * (qx - cx), cy + 2f / 3 * (qy - cy), x + 2f / 3 * (qx - x), y + 2f / 3 * (qy - y), x, y)
            lastCtrlX = qx; lastCtrlY = qy
        }

        fun convert(nodes: List<PathNode>): JSONArray {
            for (n in nodes) {
                var curve = false; var q = false
                when (n) {
                    is PathNode.MoveTo -> move(n.x, n.y)
                    is PathNode.RelativeMoveTo -> move(cx + n.dx, cy + n.dy)
                    is PathNode.LineTo -> line(n.x, n.y)
                    is PathNode.RelativeLineTo -> line(cx + n.dx, cy + n.dy)
                    is PathNode.HorizontalTo -> line(n.x, cy)
                    is PathNode.RelativeHorizontalTo -> line(cx + n.dx, cy)
                    is PathNode.VerticalTo -> line(cx, n.y)
                    is PathNode.RelativeVerticalTo -> line(cx, cy + n.dy)
                    is PathNode.CurveTo -> { cubic(n.x1, n.y1, n.x2, n.y2, n.x3, n.y3); curve = true }
                    is PathNode.RelativeCurveTo -> { cubic(cx + n.dx1, cy + n.dy1, cx + n.dx2, cy + n.dy2, cx + n.dx3, cy + n.dy3); curve = true }
                    is PathNode.ReflectiveCurveTo -> {
                        val (rx, ry) = if (lastWasCurve) (2 * cx - lastCtrlX) to (2 * cy - lastCtrlY) else cx to cy
                        cubic(rx, ry, n.x1, n.y1, n.x2, n.y2); curve = true
                    }
                    is PathNode.RelativeReflectiveCurveTo -> {
                        val (rx, ry) = if (lastWasCurve) (2 * cx - lastCtrlX) to (2 * cy - lastCtrlY) else cx to cy
                        cubic(rx, ry, cx + n.dx1, cy + n.dy1, cx + n.dx2, cy + n.dy2); curve = true
                    }
                    is PathNode.QuadTo -> { quad(n.x1, n.y1, n.x2, n.y2); q = true }
                    is PathNode.RelativeQuadTo -> { quad(cx + n.dx1, cy + n.dy1, cx + n.dx2, cy + n.dy2); q = true }
                    is PathNode.ReflectiveQuadTo -> {
                        val (rx, ry) = if (lastWasQuad) (2 * cx - lastCtrlX) to (2 * cy - lastCtrlY) else cx to cy
                        quad(rx, ry, n.x, n.y); q = true
                    }
                    is PathNode.RelativeReflectiveQuadTo -> {
                        val (rx, ry) = if (lastWasQuad) (2 * cx - lastCtrlX) to (2 * cy - lastCtrlY) else cx to cy
                        quad(rx, ry, cx + n.dx, cy + n.dy); q = true
                    }
                    is PathNode.ArcTo -> arc(n.horizontalEllipseRadius, n.verticalEllipseRadius, n.theta, n.isMoreThanHalf, n.isPositiveArc, n.arcStartX, n.arcStartY)
                    is PathNode.RelativeArcTo -> arc(n.horizontalEllipseRadius, n.verticalEllipseRadius, n.theta, n.isMoreThanHalf, n.isPositiveArc, cx + n.arcStartDx, cy + n.arcStartDy)
                    is PathNode.Close -> { out.put(JSONObject().put("type", "Close").put("points", JSONArray())); cx = sx; cy = sy }
                }
                lastWasCurve = curve; lastWasQuad = q
            }
            return out
        }

        private fun arc(rxIn: Float, ryIn: Float, angleDeg: Float, large: Boolean, sweep: Boolean, x: Float, y: Float) {
            var rx = kotlin.math.abs(rxIn); var ry = kotlin.math.abs(ryIn)
            if (rx == 0f || ry == 0f || (x == cx && y == cy)) { line(x, y); return }
            val phi = Math.toRadians(angleDeg.toDouble())
            val cosP = kotlin.math.cos(phi); val sinP = kotlin.math.sin(phi)
            val dx2 = (cx - x) / 2.0; val dy2 = (cy - y) / 2.0
            val x1p = cosP * dx2 + sinP * dy2; val y1p = -sinP * dx2 + cosP * dy2
            val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
            if (lambda > 1) { val k = kotlin.math.sqrt(lambda).toFloat(); rx *= k; ry *= k }
            val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
            val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
            var coef = kotlin.math.sqrt(kotlin.math.max(0.0, num / den))
            if (large == sweep) coef = -coef
            val cxp = coef * rx * y1p / ry; val cyp = -coef * ry * x1p / rx
            val ccx = cosP * cxp - sinP * cyp + (cx + x) / 2.0
            val ccy = sinP * cxp + cosP * cyp + (cy + y) / 2.0
            fun ang(ux: Double, uy: Double, vx: Double, vy: Double): Double {
                val a = kotlin.math.atan2(ux * vy - uy * vx, ux * vx + uy * vy); return a
            }
            val t1 = ang(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
            var dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
            if (!sweep && dt > 0) dt -= 2 * Math.PI
            if (sweep && dt < 0) dt += 2 * Math.PI
            val n = kotlin.math.ceil(kotlin.math.abs(dt) / (Math.PI / 2)).toInt().coerceAtLeast(1)
            val step = dt / n
            val k = 4.0 / 3.0 * kotlin.math.tan(step / 4)
            var t = t1
            repeat(n) {
                val c1 = kotlin.math.cos(t); val s1 = kotlin.math.sin(t)
                val c2 = kotlin.math.cos(t + step); val s2 = kotlin.math.sin(t + step)
                fun px(cs: Double, sn: Double) = (ccx + rx * cosP * cs - ry * sinP * sn).toFloat()
                fun py(cs: Double, sn: Double) = (ccy + rx * sinP * cs + ry * cosP * sn).toFloat()
                val e1x = px(c1 - k * s1, s1 + k * c1); val e1y = py(c1 - k * s1, s1 + k * c1)
                val e2x = px(c2 + k * s2, s2 - k * c2); val e2y = py(c2 + k * s2, s2 - k * c2)
                cubic(e1x, e1y, e2x, e2y, px(c2, s2), py(c2, s2))
                t += step
            }
        }
    }

    private companion object {
        /** xxhdpi — the qualifiers above. */
        const val DENSITY = 3f
        const val WIDTH_DP = 412
        const val BOTTOM_MARGIN_DP = 24f
        const val PHONE_HEIGHT_DP = 915
    }
}
