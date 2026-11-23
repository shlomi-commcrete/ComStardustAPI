package com.commcrete.stardust.util

/** Wire protocol used when sending a file. */
enum class FileSendingProtocol {
    Default, // V2 (ST1) with Reed-Solomon + PigN
    Legacy,  // V1 (STR) with gzip
}

/** Compression applied to outgoing files in V2. Ignored in Legacy mode. */
enum class FileCompressionType {
    Default,  // PigN adaptive (best compression per file type)
    GzipOnly, // Force gzip — compatible with V1 receivers that decode gzip
}
