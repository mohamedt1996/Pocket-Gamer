// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from GameNative, commit 9c5f4121eb6512707eb467d4a70edb3ae8f08ae3.
// See android/EPIC-UPSTREAM.md for provenance and local changes.

package org.pocketarcade.madeira.epic

object EpicEndpoints {
    // Public installed-launcher client identity, not the user's account credentials.
    const val CLIENT_ID = "34a02cf8f4414e29b15921876da36f9a"
    const val CLIENT_SECRET = "daafbccc737745039dffe53d94fc76cf"

    const val ACCOUNT = "https://account-public-service-prod03.ol.epicgames.com"
    const val LIBRARY = "https://library-service.live.use1a.on.epicgames.com/library/api/public/items"
    const val CATALOG = "https://catalog-public-service-prod06.ol.epicgames.com/catalog/api/shared/namespace"
    const val LAUNCHER = "https://launcher-public-service-prod06.ol.epicgames.com"
    const val AGENT = "PocketArcade/0.3 Epic integration (GameNative/Legendary compatible)"
    val loginUrl: String get() = "https://www.epicgames.com/id/login?redirectUrl=" +
        "https%3A%2F%2Fwww.epicgames.com%2Fid%2Fapi%2Fredirect%3FclientId%3D$CLIENT_ID%26responseType%3Dcode"
}
