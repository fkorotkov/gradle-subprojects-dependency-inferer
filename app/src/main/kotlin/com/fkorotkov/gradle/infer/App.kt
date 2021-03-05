package com.fkorotkov.gradle.infer

import com.fkorotkov.gradle.infer.model.JvmFile
import com.fkorotkov.gradle.infer.model.Project
import com.google.common.collect.HashMultimap
import java.io.File
import java.lang.IllegalStateException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.extension

val defaultKotlinPackageImports = setOf(
    "kotlin",
    "kotlin.annotation",
    "kotlin.collections",
    "kotlin.comparisons",
    "kotlin.io",
    "kotlin.ranges",
    "kotlin.sequences",
    "kotlin.text",
    "kotlin.test",
    "kotlinx"
)

@ExperimentalPathApi
fun main(args: Array<String>) {
    println(args.joinToString())
    val projectRoot = File(
        args.firstOrNull() ?: throw IllegalStateException("First argument should be a path")
    ).toPath()
    val projects = mutableMapOf<String, Project>()
    Files.walkFileTree(projectRoot, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (Files.exists(dir.resolve("build.gradle"))) {
                val project = Project(projectRoot.relativize(dir))
                populateProject(project, dir)
                projects[project.fqn] = project
                return FileVisitResult.CONTINUE
            }
            return FileVisitResult.CONTINUE
        }
    })
    val packagesToProjects = HashMultimap.create<String, Project>()
    projects.forEach { (_, project) ->
        project.exportedPackages.forEach { packagesToProjects.put(it, project) }
    }
    projects.forEach { (_, project) ->
        val implementation = project.importedPackages.map {
            packagesToProjects[it]
        }.flatten().map { it.fqn }.filter { it != project.fqn }.toSortedSet().map { fqn ->
            "  implementation project(\"${fqn}\")"
        }
        val testImplementation = project.importedTestPackages.map {
            packagesToProjects[it]
        }.flatten().map { it.fqn }.filter { it != project.fqn }.toSortedSet().map { fqn ->
            "  testImplementation project(\"${fqn}\")"
        }
        val buildFilePath = projectRoot.resolve(project.relativePath).resolve("build.gradle")
        val buildFileLines = Files.readAllLines(buildFilePath)
        val indexOfGeneratedDeps = buildFileLines.indexOf("dependencies { // GENERATED")
        val updatedLines =
            (if (indexOfGeneratedDeps > 0) buildFileLines.take(indexOfGeneratedDeps) else buildFileLines).toMutableList()
        updatedLines.add("dependencies { // GENERATED")
        updatedLines.addAll(implementation)
        updatedLines.addAll(testImplementation)
        updatedLines.add("}")
        Files.write(buildFilePath, updatedLines)
    }
}

@ExperimentalPathApi
private fun populateProject(project: Project, dir: Path) {
    val src = dir.resolve("src")
    if (Files.exists(src.resolve("main"))) {
        Files.walkFileTree(src.resolve("main"), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (file.extension == "kt" || file.extension == "java") {
                    project.addFile(parseJvmFile(file))
                }
                if (file.extension == "proto") {
                    project.addFile(parseProtoFile(file))
                }
                return FileVisitResult.CONTINUE
            }
        })
    }
    if (Files.exists(src.resolve("test"))) {
        Files.walkFileTree(src.resolve("test"), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (file.extension == "kt" || file.extension == "java") {
                    project.addTestFile(parseJvmFile(file))
                }
                return FileVisitResult.CONTINUE
            }
        })
    }
}

private fun parseJvmFile(path: Path): JvmFile {
    var packageFqName = ""
    val importedPackages = mutableSetOf<String>()
    Files.readAllLines(path).forEach { line ->
        if (line.startsWith("package")) {
            packageFqName = line.substringAfter("package")
                .trimStart()
                .substringBefore(' ')
                .removeSuffix(";")
        }
        if (line.startsWith("import")) {
            importedPackages.add(parseImportStatement(line).substringBeforeLast('.'))
        }
    }
    val importedPackagesFiltered = importedPackages
        .filter { !it.startsWith("java.") }
        .filter { !defaultKotlinPackageImports.contains(it.substringBeforeLast('.')) }
    return JvmFile(path, packageFqName, importedPackagesFiltered.toSortedSet())
}

fun parseProtoFile(path: Path): JvmFile {
    val line = Files.readAllLines(path).find { line ->
        line.startsWith("option java_package")
    } ?: ""
    val packageFqName = line.substringAfter('"').substringBefore('"')
    return JvmFile(path, packageFqName)
}

/*
 * Import statements can come in several forms including, this function
 * returns the following:
 * import com.fully.qualified.package.*; -> "com.fully.qualified.package.*"
 * import static com.fully.qualified.package.Type; -> "com.fully.qualified.package.Type"
 */
private fun parseImportStatement(line: String): String {
    val parts = line.split(" ")

    val fqn = if (parts.size == 3) {
        if (parts[1] == "static") parts[2] else error("Invalid import statement: $line")
    } else if (parts.size == 2) {
        parts[1]
    } else {
        error("Invalid import statement: $line")
    }

    return fqn.removeSuffix(";")
}
