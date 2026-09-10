package me.erguotou.homehub.ui.screens.album

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.AMapOptions
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.CoordinateConverter
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.MarkerOptions
import me.erguotou.homehub.BuildConfig
import me.erguotou.homehub.data.GeoPoint
import java.util.Locale

/**
 * AMap (高德) map rendering the server-side geo aggregation.
 *
 * Notes:
 * * the SDK key is baked into the build (`BuildConfig.AMAP_KEY`) and injected
 *   through [MapsInitializer.setApiKey] — the map is part of the product, so
 *   there is nothing for the user to configure;
 * * EXIF GPS is WGS-84 while AMap draws in GCJ-02, so every cluster is
 *   converted with [CoordinateConverter] before it becomes a marker.
 */
@Composable
fun AMapView(
    context: Context,
    points: List<GeoPoint>,
    onSelect: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var map by remember { mutableStateOf<AMap?>(null) }

    val mapView = remember(context) {
        // 高德 SDK 10.x 强制要求先表态隐私合规，否则引擎初始化了也不出图
        // （表现为整块浅灰、无瓦片，logcat 里也没有授权报错）。
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)
        MapsInitializer.setApiKey(BuildConfig.AMAP_KEY)
        MapView(context, AMapOptions().apply { zoomControlsEnabled(false) })
    }

    DisposableEffect(lifecycle, mapView) {
        mapView.onCreate(null)
        // Pause/resume follow the activity; teardown happens in onDispose below
        // (calling onDestroy twice is not safe).
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            runCatching { mapView.onDestroy() }
        }
    }

    LaunchedEffect(mapView) {
        map = mapView.map
    }

    // Redraw markers whenever the aggregation changes.
    LaunchedEffect(points, map) {
        val aMap = map ?: return@LaunchedEffect
        aMap.clear()
        if (points.isEmpty()) return@LaunchedEffect

        val builder = LatLngBounds.Builder()
        val max = points.maxOf { it.count.coerceAtLeast(1) }
        points.forEach { point ->
            val gcj = toGcj02(context, point.lat, point.lng)
            builder.include(gcj)
            val hue = when {
                point.count.toFloat() / max > 0.66f -> BitmapDescriptorFactory.HUE_RED
                point.count.toFloat() / max > 0.33f -> BitmapDescriptorFactory.HUE_ORANGE
                else -> BitmapDescriptorFactory.HUE_BLUE
            }
            aMap.addMarker(
                MarkerOptions()
                    .position(gcj)
                    .title("${point.count} 张")
                    .snippet(String.format(Locale.US, "%.5f, %.5f", point.lat, point.lng))
                    .icon(countBadge(context, point.count, hue))
                    .anchor(0.5f, 0.5f)
            )?.setObject(point)
        }

        aMap.setOnMarkerClickListener { marker ->
            val point = marker.`object` as? GeoPoint
            if (point != null) {
                onSelect(point)
                true
            } else {
                false
            }
        }
        aMap.moveCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 80))
    }

    AndroidView(factory = { mapView }, modifier = modifier.fillMaxSize())
}

/** WGS-84 -> GCJ-02 through the SDK converter (falls back to the raw point). */
private fun toGcj02(context: Context, lat: Double, lng: Double): LatLng {
    if (!CoordinateConverter.isAMapDataAvailable(lat, lng)) return LatLng(lat, lng)
    // `convert()` is a Java platform type: guard against a null result.
    @Suppress("TooGenericExceptionCaught")
    val converted = try {
        CoordinateConverter(context)
            .from(CoordinateConverter.CoordType.GPS)
            .coord(LatLng(lat, lng))
            .convert()
    } catch (e: Exception) {
        null
    }
    return converted ?: LatLng(lat, lng)
}

/**
 * Round badge showing the cluster's photo count, tinted by density like the
 * pin hues were. Matches the PC web map, where every cluster shows its number.
 */
private fun countBadge(context: Context, count: Long, hue: Float): BitmapDescriptor {
    val density = context.resources.displayMetrics.density
    val size = (38 * density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = when {
            hue == BitmapDescriptorFactory.HUE_RED -> Color.argb(235, 229, 57, 53)
            hue == BitmapDescriptorFactory.HUE_ORANGE -> Color.argb(235, 251, 140, 0)
            else -> Color.argb(235, 30, 136, 229)
        }
        style = Paint.Style.FILL
    }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }
    val cx = size / 2f
    val radius = size / 2f - 2 * density
    canvas.drawCircle(cx, cx, radius, fill)
    canvas.drawCircle(cx, cx, radius, stroke)

    val text = if (count > 999) "999+" else count.toString()
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = (if (text.length > 3) 12 else if (text.length > 2) 14 else 16) * density
        isFakeBoldText = true
    }
    val baseline = cx - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(text, cx, baseline, textPaint)

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}
