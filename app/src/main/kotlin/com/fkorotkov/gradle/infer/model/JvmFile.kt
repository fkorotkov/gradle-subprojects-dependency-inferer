package com.fkorotkov.gradle.infer.model

import java.nio.file.Path

data class JvmFile(
    val path: Path,
    val packageFqName: String,
    val importedPackages: Set<String> = emptySet()
)