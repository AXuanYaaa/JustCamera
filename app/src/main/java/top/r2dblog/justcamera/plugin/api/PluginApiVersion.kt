package top.r2dblog.justcamera.plugin.api

data class PluginApiVersion(val major: Int, val minor: Int, val patch: Int = 0) {
    init {
        require(major in 0..255 && minor in 0..255 && patch in 0..65535)
    }

    fun encode(): UInt =
        (major.toUInt() shl 24) or (minor.toUInt() shl 16) or patch.toUInt()

    /** A host accepts an older/equal minor revision within the same ABI major. */
    fun supports(requiredByPlugin: PluginApiVersion): Boolean =
        major == requiredByPlugin.major && minor >= requiredByPlugin.minor

    companion object {
        val HOST = PluginApiVersion(1, 0, 0)

        fun decode(encoded: UInt) = PluginApiVersion(
            major = (encoded shr 24).toInt() and 0xff,
            minor = (encoded shr 16).toInt() and 0xff,
            patch = encoded.toInt() and 0xffff,
        )
    }
}
