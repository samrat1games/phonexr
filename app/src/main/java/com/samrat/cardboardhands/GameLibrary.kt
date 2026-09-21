package com.samrat.cardboardhands

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.File
import java.util.zip.ZipFile

/** Installed VR games PhoneXR can start, found by what their APK carries. */
object GameLibrary {
    enum class Kind {
        /** OpenXR game that finds the PhoneXR runtime, starts as is. */
        OPENXR,
        /** OpenXR game built for a headset: on a phone it finds no runtime and shows a black screen. */
        OPENXR_ORIGINAL,
        /** VrApi game (Quest or Gear VR) with the PhoneXR adapter in place of libvrapi.so. */
        VRAPI_READY,
        /** VrApi game as it came from the store: needs patching before it runs. */
        VRAPI_ORIGINAL,
        /** VrApi game that cannot be patched: it has no ARM build. */
        VRAPI_UNSUPPORTED,
        /** Google VR (Daydream or Cardboard) game: draws its own stereo view, starts as is. */
        DAYDREAM
    }

    /** The headset a build was made for. Quest and Gear VR both draw through VrApi, so the
     *  manifest — not the library — says which one it is. */
    enum class Headset(val title: String) { QUEST("Quest"), GEAR_VR("Gear VR"), UNKNOWN("VR‑гарнитура") }

    data class Game(
        val packageName: String,
        val label: String,
        val kind: Kind,
        val apk: File,
        val hasSplits: Boolean,
        /** Carries the Oculus store purchase check; it may or may not stop the game. */
        val checksPurchase: Boolean = false,
        val headset: Headset = Headset.UNKNOWN
    )

    private val entitlement = setOf(
        "libovrplatformloader.so", "libOVRPlatformLoader.so", "libovrplatform.so", "libOVRPlatform.so"
    )
    private val vrCategories = listOf(
        "org.khronos.openxr.intent.category.IMMERSIVE_HMD",
        "com.oculus.intent.category.VR",
        DAYDREAM_CATEGORY,
        CARDBOARD_CATEGORY
    )
    private const val DAYDREAM_CATEGORY = "com.google.intent.category.DAYDREAM"
    private const val CARDBOARD_CATEGORY = "com.google.intent.category.CARDBOARD"
    /** Google VR Services: Daydream-only games will not start without it. */
    const val VR_SERVICES = "com.google.vr.vrcore"

    /** Slow (opens every candidate APK), call off the main thread. */
    fun scan(context: Context): List<Game> {
        val packages = context.packageManager
        val vrActivities = vrCategories.flatMap { category ->
            packages.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), PackageManager.MATCH_ALL)
        }
        val declaredVr = vrActivities.map { it.activityInfo.packageName }.toSet()
        val googleVr = listOf(DAYDREAM_CATEGORY, CARDBOARD_CATEGORY).flatMap { category ->
            packages.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), PackageManager.MATCH_ALL)
        }.map { it.activityInfo.packageName }.toSet()
        // A browser counts only when the activity it launches with is itself a VR one (Wolvic);
        // Chrome declares a separate VR activity for WebXR but opens as a normal browser.
        val vrLaunchers = vrActivities.map { it.activityInfo.packageName + "/" + it.activityInfo.name }.toSet()
        fun launchesIntoVr(packageName: String) =
            packages.getLaunchIntentForPackage(packageName)?.component?.let { "${it.packageName}/${it.className}" in vrLaunchers } == true
        val browsers = packages.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com")), PackageManager.MATCH_ALL
        ).map { it.activityInfo.packageName }.toSet()

        return packages.getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != context.packageName }
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || it.packageName in declaredVr }
            .mapNotNull { info ->
                classify(info, info.packageName in declaredVr, info.packageName in googleVr)?.let { kind -> info to kind }
            }
            .map { (info, kind) -> Triple(info, kind, hasPurchaseCheck(info)) }
            // Browsers carry the OpenXR loader for WebXR but are not games, unless they declare VR (Wolvic).
            .filterNot { (info, kind, _) ->
                (kind == Kind.OPENXR || kind == Kind.OPENXR_ORIGINAL) &&
                    info.packageName in browsers && !launchesIntoVr(info.packageName)
            }
            .map { (info, kind, checksPurchase) ->
                Game(
                    packageName = info.packageName,
                    label = info.loadLabel(packages).toString(),
                    kind = kind,
                    apk = File(info.sourceDir),
                    hasSplits = !info.splitSourceDirs.isNullOrEmpty(),
                    checksPurchase = checksPurchase,
                    headset = headset(info)
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /**
     * Reads the installed manifest: a build made for a headset asks for no OpenXR permission and
     * keeps package visibility on, so it never finds the runtime. PhoneXR offers to patch those.
     */
    private fun findsRuntime(info: ApplicationInfo) = runCatching {
        ZipFile(info.sourceDir).use { zip ->
            val manifest = zip.getEntry("AndroidManifest.xml") ?: return@use true
            AndroidManifestPatcher.findsRuntime(zip.getInputStream(manifest).use { it.readBytes() })
        }
    }.getOrDefault(true)

    /**
     * Which headset a build was made for. Quest builds name the devices they run on; Gear VR and Go
     * builds name Samsung's VR mode and are 32-bit, because no 64-bit Gear VR ever existed.
     */
    fun headsetOf(markers: Set<String>, sixtyFour: Boolean): Headset = when {
        markers.any { it.startsWith("com.oculus.supportedDevices") } -> Headset.QUEST
        markers.any { it == "com.samsung.android.vr.application.mode" } -> Headset.GEAR_VR
        markers.any { it.startsWith("com.oculus.") } -> Headset.QUEST
        sixtyFour -> Headset.QUEST
        else -> Headset.GEAR_VR
    }

    private fun headset(info: ApplicationInfo): Headset = runCatching {
        ZipFile(info.sourceDir).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: return@use Headset.UNKNOWN
            val markers = AndroidManifestPatcher.markers(zip.getInputStream(entry).use { it.readBytes() })
            headsetOf(markers, zip.entries().toList().any { it.name.startsWith("lib/arm64-v8a/") })
        }
    }.getOrDefault(Headset.UNKNOWN)

    private fun hasPurchaseCheck(info: ApplicationInfo) = runCatching {
        ZipFile(info.sourceDir).use { zip ->
            zip.entries().toList().any { it.name.startsWith("lib/") && it.name.substringAfterLast('/') in entitlement }
        }
    }.getOrDefault(false)

    private fun classify(info: ApplicationInfo, declaredVr: Boolean, googleVr: Boolean): Kind? {
        val names = runCatching { ZipFile(info.sourceDir).use { zip -> zip.entries().toList().map { it.name } } }
            .getOrNull() ?: return null
        val libs = names.filter { it.startsWith("lib/") }
        val vrapi = libs.filter { it.endsWith("/libvrapi.so") }
        if (vrapi.isEmpty()) {
            val openXr = libs.any { it.endsWith("/libopenxr_loader.so") }
            val gvr = googleVr || libs.any { it.endsWith("/libgvr.so") || it.endsWith("/libgvr_audio.so") }
            return when {
                openXr || declaredVr -> if (findsRuntime(info)) Kind.OPENXR else Kind.OPENXR_ORIGINAL
                gvr -> Kind.DAYDREAM
                else -> null
            }
        }
        // The adapter exists for 64-bit and 32-bit ARM; anything else (x86 only) cannot run it.
        val folder = vrapi.map { it.removePrefix("lib/").substringBefore('/') }
            .firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" || it == "armeabi" }
            ?: return Kind.VRAPI_UNSUPPORTED
        // The patcher adds the OpenXR loader next to the adapter; store builds never carry it.
        return if ("lib/$folder/libopenxr_loader.so" in libs) Kind.VRAPI_READY else Kind.VRAPI_ORIGINAL
    }

    /**
     * The game tracks hands with the camera itself (<meta-data android:name="com.phonexr.OWN_HAND_TRACKING"
     * android:value="true" /> in its manifest): PhoneXR gives the camera up instead of starting its own tracking.
     */
    const val OWN_HAND_TRACKING = "com.phonexr.OWN_HAND_TRACKING"

    fun ownsHandTracking(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA).metaData?.getBoolean(OWN_HAND_TRACKING) == true
    }.getOrDefault(false)

    fun launchIntent(context: Context, game: Game): Intent? =
        // Gear VR games declare MAIN + INFO instead of a launcher entry; this finds both.
        context.packageManager.getLaunchIntentForPackage(game.packageName)
            ?: vrCategories.firstNotNullOfOrNull { category ->
                context.packageManager.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(game.packageName), 0
                ).firstOrNull()?.activityInfo?.let { activity ->
                    Intent(Intent.ACTION_MAIN).setClassName(activity.packageName, activity.name)
                }
            }

    /** The line under the game's name in the list: what it is and what PhoneXR has to do with it. */
    fun describe(game: Game) = when (game.kind) {
        Kind.OPENXR -> "OpenXR"
        Kind.OPENXR_ORIGINAL -> "${game.headset.title} · OpenXR · нужно пропатчить"
        Kind.VRAPI_READY -> "${game.headset.title} · через переходник PhoneXR"
        Kind.VRAPI_ORIGINAL -> "${game.headset.title} · VrApi · нужно пропатчить"
        Kind.VRAPI_UNSUPPORTED -> "${game.headset.title} · не поддерживается"
        Kind.DAYDREAM -> "Daydream / Cardboard"
    }
}
