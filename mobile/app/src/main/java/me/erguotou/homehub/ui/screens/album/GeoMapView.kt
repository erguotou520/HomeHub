package me.erguotou.homehub.ui.screens.album

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.MarkerOptions
import me.erguotou.homehub.data.GeoPoint
import me.erguotou.homehub.data.Prefs
import java.util.Locale

/**
 * AMap (高德) map rendering the server-side geo aggregation.
 *
 * Notes:
 * * the SDK key is injected at runtime through [MapsInitializer.setApiKey] so
 *   nothing has to be baked into the manifest;
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
    val prefs = remember(context) { Prefs(context) }
    var map by remember { mutableStateOf<AMap?>(null) }

    if (prefs.amapKey.isBlank()) {
        MissingKeyHint()
        return
    }

    val mapView = remember(context) {
        MapsInitializer.setApiKey(prefs.amapKey)
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
                    .icon(BitmapDescriptorFactory.defaultMarker(hue))
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

/** Shown until the user registers an AMap key in 设置 → 地图. */
@Composable
private fun MissingKeyHint() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "需要高德地图 Key",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "在「设置 → 地图」中填入高德 Android SDK Key 后即可按地点浏览照片。" +
                    "服务端已提供经纬度聚合数据，地图渲染由高德 SDK 完成。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
