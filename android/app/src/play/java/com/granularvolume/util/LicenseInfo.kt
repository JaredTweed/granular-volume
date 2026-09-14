package com.granularvolume.util

/**
 * Play build: the same open-source components as the F-Droid build, plus one proprietary Google
 * library.
 *
 * The in-app review library is deliberately listed separately rather than folded into the
 * open-source list, because it is **not** open source: it is distributed under Google's Play Core
 * Software Development Kit Terms of Service. Calling it open source on a licences screen would be
 * exactly the kind of small false statement this screen exists to prevent.
 */
object LicenseInfo {

    /** name to licence, shown in order. */
    val openSource: List<Pair<String, String>> = listOf(
        "AndroidX Core KTX" to "The Android Open Source Project, Apache License 2.0",
        "AndroidX AppCompat" to "The Android Open Source Project, Apache License 2.0",
        "AndroidX Lifecycle Service" to "The Android Open Source Project, Apache License 2.0",
        "Material Components for Android" to "Google LLC, Apache License 2.0",
        "Kotlin Coroutines for Android" to "JetBrains s.r.o., Apache License 2.0",
        "Kotlin Standard Library" to "JetBrains s.r.o. and Kotlin Programming Language contributors, Apache License 2.0",
    )

    val proprietary: List<Pair<String, String>> = listOf(
        "Google Play In-App Review library" to
            "Google LLC. Not open source: distributed under the Play Core Software Development " +
            "Kit Terms of Service. Used only to display Google's own rate-this-app dialog, and " +
            "absent from the F-Droid build.",
    )
}
