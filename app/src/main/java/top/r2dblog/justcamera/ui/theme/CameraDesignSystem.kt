package top.r2dblog.justcamera.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object CameraColors {
    val Background = Color(0xFF08090B)
    val Overlay = Color(0xB8000000)
    val ControlSurface = Color(0xA624272C)
    val ControlSurfaceSelected = Color(0xE6E7C66A)
    val PrimaryContent = Color(0xFFF7F7F8)
    val SecondaryContent = Color(0xB8F7F7F8)
    val Accent = Color(0xFFE7C66A)
}

object CameraSpacing {
    val Small = 4.dp
    val Medium = 8.dp
    val Large = 16.dp
    val ExtraLarge = 24.dp
}

object CameraDimensions {
    val TouchTarget = 48.dp
    val Icon = 24.dp
    val Shutter = 78.dp
    val ShutterInner = 58.dp
}

object CameraShapes {
    val Control = RoundedCornerShape(12.dp)
    val Panel = RoundedCornerShape(18.dp)
}
