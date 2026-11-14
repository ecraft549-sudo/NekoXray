package com.example.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
sealed interface NekoRoute {
    @Serializable
    data object Profiles : NekoRoute

    @Serializable
    data object Groups : NekoRoute

    @Serializable
    data object Routing : NekoRoute

    @Serializable
    data object Tools : NekoRoute

    @Serializable
    data object Settings : NekoRoute

    @Serializable
    data class ProfileEditor(val profileId: Long = 0L, val initialProtocol: String = "VLESS") : NekoRoute

    @Serializable
    data class ConfigPreview(val profileId: Long) : NekoRoute

    @Serializable
    data object AppManager : NekoRoute
}
