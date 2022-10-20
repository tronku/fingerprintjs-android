package com.fingerprintjs.android.fingerprint

import androidx.annotation.WorkerThread
import com.fingerprintjs.android.fingerprint.device_id_signals.DeviceIdSignalsProvider
import com.fingerprintjs.android.fingerprint.fingerprinting_signals.FingerprintingSignal
import com.fingerprintjs.android.fingerprint.fingerprinting_signals.FingerprintingSignalsProvider
import com.fingerprintjs.android.fingerprint.signal_providers.SignalGroupProvider
import com.fingerprintjs.android.fingerprint.signal_providers.StabilityLevel
import com.fingerprintjs.android.fingerprint.signal_providers.device_id.DeviceIdProvider
import com.fingerprintjs.android.fingerprint.signal_providers.device_state.DeviceStateSignalGroupProvider
import com.fingerprintjs.android.fingerprint.signal_providers.hardware.HardwareSignalGroupProvider
import com.fingerprintjs.android.fingerprint.signal_providers.installed_apps.InstalledAppsSignalGroupProvider
import com.fingerprintjs.android.fingerprint.signal_providers.os_build.OsBuildSignalGroupProvider
import com.fingerprintjs.android.fingerprint.tools.DeprecationMessages
import com.fingerprintjs.android.fingerprint.tools.FingerprintingLegacySchemeSupportExtensions
import com.fingerprintjs.android.fingerprint.tools.hashers.Hasher
import com.fingerprintjs.android.fingerprint.tools.hashers.MurMur3x64x128Hasher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


public class Fingerprinter internal constructor(
    private val legacyArgs: LegacyArgs?,
    private val fpSignalsProvider: FingerprintingSignalsProvider,
    private val deviceIdSignalsProvider: DeviceIdSignalsProvider,
) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private var deviceIdResult: DeviceIdResult? = null
    private var fingerprintResult: FingerprintResult? = null

    /**
     * Retrieve device ID information.
     *
     * Subsequent calls will return the cached result.
     *
     * This method and [Configuration] class are now deprecated in favor of getDeviceId(version, listener)
     * which gives an ability to get device id with different parameters without reinstantiation of the whole [Fingerprinter] class.
     *
     * @param listener device ID listener.
     * @throws IllegalStateException if [Fingerprinter] was retrieved via [FingerprinterFactory.create] method.
     */
    @Deprecated(message = """
        This method has been deprecated in favor of getDeviceId(version, listener) overload. Check out method doc for details.
    """)
    @Throws(IllegalStateException::class)
    public fun getDeviceId(listener: (DeviceIdResult) -> Unit) {
        if (legacyArgs == null) {
            throw (IllegalStateException(
                "To call this deprecated method, the instance" +
                        "must be retrieved using deprecated factory method."
            ))
        }

        deviceIdResult?.let {
            listener.invoke(it)
            return
        }

        executor.execute {
            val deviceIdResult = DeviceIdResult(
                legacyArgs.deviceIdProvider.fingerprint(),
                legacyArgs.deviceIdProvider.rawData().gsfId().value,
                legacyArgs.deviceIdProvider.rawData().androidId().value,
                legacyArgs.deviceIdProvider.rawData().mediaDrmId().value
            )
            this.deviceIdResult = deviceIdResult
            listener.invoke(deviceIdResult)
        }
    }

    /**
     * Retrieve device ID information.
     *
     * Device IDs are cached internally, so it makes no sense to call this method more than once with the same [version].
     * If you suddenly want to re-evaluate device id completely from scratch, you should reinitialize [Fingerprinter] using
     * [FingerprinterFactory.create] method.
     *
     * @param version identification version. Check out [IdentificationVersion] for details.
     * @param listener device ID listener.
     */
    public fun getDeviceId(version: IdentificationVersion, listener: (DeviceIdResult) -> Unit) {
        executor.execute {
            listener.invoke(
                DeviceIdResult(
                    deviceId = deviceIdSignalsProvider.getSignalMatching(version).getIdString(),
                    gsfId = deviceIdSignalsProvider.gsfIdSignal.getIdString(),
                    androidId = deviceIdSignalsProvider.androidIdSignal.getIdString(),
                    mediaDrmId = deviceIdSignalsProvider.mediaDrmIdSignal.getIdString(),
                )
            )
        }
    }

    /**
     * Retrieve device fingerprint information.
     *
     * Fingerprinting signals are cached internally, so it makes no sense to call this method more than once with the same [stabilityLevel].
     *
     * This method and [Configuration] class are now deprecated in favor of getFingerprint(version,stabilityLevel, hasher, listener)
     * which gives an ability to get fingerprint with different parameters without reinstantiation of the whole [Fingerprinter] class.
     * Check [FingerprintResult] documentation on how it's properties can be replaced with the newer API.
     *
     * @param stabilityLevel stability level. Check out [StabilityLevel] for details.
     * @param listener listener for [FingerprintResult].
     * @throws IllegalStateException if [Fingerprinter] was retrieved via [FingerprinterFactory.create] method.
     */
    @Deprecated("""
        This method is deprecated in favor of getFingerprint(version,stabilityLevel, hasher, listener)
        overload. Check out method doc for details.
        """)
    @Throws(IllegalStateException::class)
    @JvmOverloads
    public fun getFingerprint(
        stabilityLevel: StabilityLevel = StabilityLevel.OPTIMAL,
        listener: (FingerprintResult) -> Unit
    ) {
        if (legacyArgs == null) {
            throw (IllegalStateException(
                "To call this deprecated method, the instance" +
                        "must be retrieved using deprecated factory method."
            ))
        }

        fingerprintResult?.let {
            listener.invoke(it)
            return
        }
        executor.execute {
            val fingerprintSb = StringBuilder()

            fingerprintSb.apply {
                append(legacyArgs.hardwareSignalProvider.fingerprint(stabilityLevel))
                append(legacyArgs.osBuildSignalProvider.fingerprint(stabilityLevel))
                append(legacyArgs.deviceStateSignalProvider.fingerprint(stabilityLevel))
                append(legacyArgs.installedAppsSignalProvider.fingerprint(stabilityLevel))
            }

            val result = object : FingerprintResult {
                override val fingerprint = legacyArgs.configuration.hasher.hash(fingerprintSb.toString())

                @Suppress("UNCHECKED_CAST")
                override fun <T : SignalGroupProvider<*>> getSignalProvider(clazz: Class<T>): T? {
                    return when (clazz) {
                        HardwareSignalGroupProvider::class.java -> legacyArgs.hardwareSignalProvider
                        OsBuildSignalGroupProvider::class.java -> legacyArgs.osBuildSignalProvider
                        DeviceStateSignalGroupProvider::class.java -> legacyArgs.deviceStateSignalProvider
                        InstalledAppsSignalGroupProvider::class.java -> legacyArgs.installedAppsSignalProvider
                        DeviceIdProvider::class.java -> legacyArgs.deviceIdProvider
                        else -> null
                    } as? T
                }
            }

            listener.invoke(result)
        }
    }

    /**
     * Retrieve device fingerprint information. This is the most convenient method to simply get device fingerprint.
     *
     * Fingerprinting signals are cached internally, so it makes no sense to call this method more than once with the same parameters.
     * If you suddenly want to re-evaluate fingerprint completely from scratch, you should reinitialize [Fingerprinter] using
     * [FingerprinterFactory.create] method.
     *
     * @param version identification version. Check out [IdentificationVersion] for details.
     * @param stabilityLevel stability level. Check out [StabilityLevel] for details.
     * @param hasher hash implementation. Check out [Hasher] for details.
     * @param listener listener for device fingerprint.
     */
    @JvmOverloads
    public fun getFingerprint(
        version: IdentificationVersion,
        stabilityLevel: StabilityLevel = StabilityLevel.OPTIMAL,
        hasher: Hasher = MurMur3x64x128Hasher(),
        listener: (String) -> (Unit),
    ) {
        executor.execute {

            val result = if (version < IdentificationVersion.fingerprintingFlattenedSignalsFirstVersion) {
                val joinedHashes = with(FingerprintingLegacySchemeSupportExtensions) {
                    listOf(
                        hasher.hash(fpSignalsProvider.getHardwareSignals(version, stabilityLevel)),
                        hasher.hash(fpSignalsProvider.getOsBuildSignals(version, stabilityLevel)),
                        hasher.hash(fpSignalsProvider.getDeviceStateSignals(version, stabilityLevel)),
                        hasher.hash(fpSignalsProvider.getInstalledAppsSignals(version, stabilityLevel)),
                    ).joinToString(separator = "")
                }

                hasher.hash(joinedHashes)
            } else {
                getFingerprint(
                    fingerprintingSignals = fpSignalsProvider.getSignalsMatching(version, stabilityLevel),
                    hasher = hasher,
                )
            }

            listener.invoke(result)
        }
    }

    /**
     * A method for retrieving a custom fingerprint using only provided [fingerprintingSignals].
     * The signals are supposed to be retrieved using [FingerprintingSignalsProvider], which in turn is retrieved
     * via [getFingerprintingSignalsProvider].
     *
     * Note that if you use this method (along with [FingerprintingSignalsProvider's][FingerprintingSignalsProvider]
     * synchronous methods), you should take responsibility for moving the work into a background thread to avoid potential
     * frame drops or ANRs.
     *
     * @param fingerprintingSignals signals used to retrieve device fingerprint.
     * @param hasher hash implementation. Check out [Hasher] for details.
     * @return Device fingerpint.
     */
    @WorkerThread
    public fun getFingerprint(
        fingerprintingSignals: List<FingerprintingSignal<*>>,
        hasher: Hasher = MurMur3x64x128Hasher(),
    ): String {
        return hasher.hash(fingerprintingSignals)
    }

    private fun Hasher.hash(fingerprintingSignals: List<FingerprintingSignal<*>>): String {
        val joinedString =
            fingerprintingSignals.joinToString(separator = "") { it.getHashableString() }
        return this.hash(joinedString)
    }

    /**
     * @return [FingerprintingSignalsProvider] which is useful in conjunction with getFingerprint(signals, hasher) method.
     */
    public fun getFingerprintingSignalsProvider(): FingerprintingSignalsProvider {
        return fpSignalsProvider
    }

    internal data class LegacyArgs(
        val hardwareSignalProvider: HardwareSignalGroupProvider,
        val osBuildSignalProvider: OsBuildSignalGroupProvider,
        val deviceIdProvider: DeviceIdProvider,
        val installedAppsSignalProvider: InstalledAppsSignalGroupProvider,
        val deviceStateSignalProvider: DeviceStateSignalGroupProvider,
        val configuration: Configuration
    )
}

/**
 * Represents device fingerprint information.
 *
 * This class is deprecated along with [Fingerprinter's][Fingerprinter] getFingerprint(stabilityLevel, listener) method.
 * Rules for migrating to newer APIs are listed below.
 *
 * If you relied only on [fingerprint] property, you can easily retrieve it using the new
 * getFingerprint(version, stabilityLevel, hasher, listener) method.
 *
 * If you relied on [SignalGroupProvider.rawData] to retrieve any specific signals, you can now retrieve them
 * using [FingerprintingSignalsProvider]. [FingerprintingSignalsProvider] makes it easier, because it represents all
 * signals in one place, so there is no more need to access different signals via different [SignalGroupProvider]s.
 *
 * Lastly, if you relied on [SignalGroupProvider.fingerprint] which essentially returned a fingerprint of a whole
 * *group* of signals, then there is no any one-line alternative in the newer API, because we intentionally removed the
 * *grouping* of signals and made signal's hierarchy flat. That means that now you will have to pick a set of signals by yourself
 * via [FingerprintingSignalsProvider] and pass them to [Fingerprinter]'s getFingerprint(fingerprintingSignals, hasher) method.
 */
@Deprecated(message = DeprecationMessages.DEPRECATED_SYMBOL)
public interface FingerprintResult {
    public val fingerprint: String
    public fun <T : SignalGroupProvider<*>> getSignalProvider(clazz: Class<T>): T?
}

/**
 * Represents device ID information.
 * @property deviceId This is the one of the IDs listed below – the one that we consider the most appropriate
 * as a default device identifier.
 * @property gsfId GSF ID.
 * @property androidId Android ID.
 * @property mediaDrmId Media DRM ID.
 */
public data class DeviceIdResult(
    val deviceId: String,
    val gsfId: String,
    val androidId: String,
    val mediaDrmId: String,
)
