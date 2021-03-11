package com.fkorotkov.gradle.infer.model

import java.nio.file.Path

class Project(val fqn: String, val relativePath: Path) {
    constructor(relativePath: Path): this(
        relativePath.iterator().asSequence().joinToString(separator = ":", prefix = ":"),
        relativePath
    )

    val exportedPackages = mutableSetOf<String>()
    val importedPackages = mutableSetOf<String>()
    val transtitivePackages = mutableSetOf<String>()
    val importedTestPackages = mutableSetOf<String>()

    fun addFile(file: JvmFile) {
        exportedPackages.add(file.packageFqName)
        importedPackages.addAll(file.importedPackages)
        transtitivePackages.addAll(file.transitivePackages)
    }

    fun addTestFile(file: JvmFile) {
        importedTestPackages.addAll(file.importedPackages)
    }
}