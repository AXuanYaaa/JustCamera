package top.r2dblog.justcamera.plugin.host

import java.io.File
import top.r2dblog.justcamera.plugin.model.PluginDescriptor

data class PluginCandidate(val file: File, val expectedSha256: String)

interface PluginLoader {
    suspend fun load(candidate: PluginCandidate): Result<PluginDescriptor>
}

/** PH1 deliberately refuses external executable code until the install trust flow exists. */
class DisabledExternalPluginLoader : PluginLoader {
    override suspend fun load(candidate: PluginCandidate): Result<PluginDescriptor> =
        Result.failure(
            PluginLoadingDisabledException(
                "External native plugins are disabled in PH1: ${candidate.file.name}",
            ),
        )
}

class PluginLoadingDisabledException(message: String) : IllegalStateException(message)
