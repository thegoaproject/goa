package com.goa.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Material 3 Expressive Shape Tokens (Squircle radii and pill shapes)
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),      // Expressive game card squircle
    large = RoundedCornerShape(28.dp),       // Modal sheet & container curve
    extraLarge = RoundedCornerShape(36.dp)   // Hero banners & prominent surfaces
)

val PillShape = CircleShape
val BadgeShape = RoundedCornerShape(6.dp)

