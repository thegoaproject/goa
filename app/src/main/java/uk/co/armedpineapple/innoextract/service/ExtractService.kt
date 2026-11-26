package uk.co.armedpineapple.innoextract.service

import java.io.File

class ExtractService(
    private val onMessage: ((line: String, streamType: Int) -> Unit)? = null
) {
    companion object {
        init {
            try {
                System.loadLibrary("innoextract")
            } catch (e: UnsatisfiedLinkError) {
                // Allows unit test execution without native library
            }
        }
    }

    // Called directly from native C++ innoextract logging
    fun gotString(line: String, streamType: Int) {
        onMessage?.invoke(line, streamType)
    }

    private external fun nativeInit()
    private external fun nativeDoExtract(installerPath: String, outputDirectory: String): Int

    fun extract(installerFile: File, outputDir: File): Int {
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        nativeInit()
        return nativeDoExtract(installerFile.absolutePath, outputDir.absolutePath)
    }
}
