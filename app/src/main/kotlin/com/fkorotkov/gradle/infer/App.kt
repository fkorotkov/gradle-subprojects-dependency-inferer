package com.fkorotkov.gradle.infer

import com.fkorotkov.gradle.infer.model.JvmFile
import com.fkorotkov.gradle.infer.model.Project
import com.google.common.collect.HashMultimap
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiUtil
import com.intellij.psi.util.PsiUtilCore
import com.intellij.psi.util.findDescendantOfType
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.quickfix.createFromUsage.callableBuilder.getReturnTypeReference
import org.jetbrains.kotlin.nj2k.postProcessing.type
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.findDescendantOfType
import org.jetbrains.kotlin.psi.psiUtil.getSuperNames
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
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
fun generate(psiManager: PsiManager, projectRoot: Path) {
    val projects = mutableMapOf<String, Project>()
    Files.walkFileTree(projectRoot, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (Files.exists(dir.resolve("build.gradle"))) {
                val project = Project(projectRoot.relativize(dir))
                populateProject(project, psiManager, dir)
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
        val api = project.transtitivePackages.map {
            packagesToProjects[it]
        }.flatten().map { it.fqn }.filter { it != project.fqn }.toSortedSet().map { fqn ->
            "  api project(\"${fqn}\")"
        }
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
        updatedLines.addAll(api)
        updatedLines.addAll(implementation)
        updatedLines.addAll(testImplementation)
        updatedLines.add("}")
        Files.write(buildFilePath, updatedLines)
    }
}

@ExperimentalPathApi
private fun populateProject(project: Project, psiManager: PsiManager, dir: Path) {
    val src = dir.resolve("src")
    if (Files.exists(src.resolve("main"))) {
        Files.walkFileTree(src.resolve("main"), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (file.extension == "kt" || file.extension == "java") {
                    project.addFile(parseJvmFile(psiManager, file))
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
                    project.addTestFile(parseJvmFile(psiManager, file))
                }
                return FileVisitResult.CONTINUE
            }
        })
    }
}

@ExperimentalPathApi
private fun parseJvmFile(psiManager: PsiManager, path: Path): JvmFile {
    var packageFqName = ""
    val importedPackages = mutableSetOf<String>()
    val importedClassToPackages = mutableMapOf<String, String>()
    Files.readAllLines(path).forEach { line ->
        if (line.startsWith("package")) {
            packageFqName = line.substringAfter("package")
                .trimStart()
                .substringBefore(' ')
                .removeSuffix(";")
        }
        if (line.startsWith("import")) {
            val importStatement = parseImportStatement(line)
            val packageFromImport = importStatement.substringBeforeLast('.')
            val classNameFromImport = importStatement.substringAfterLast('.')
            importedPackages.add(packageFromImport)
            importedClassToPackages[classNameFromImport] = packageFqName
        }
    }
    val importedPackagesFiltered = importedPackages
        .filter { !it.startsWith("java.") }
        .filter { !defaultKotlinPackageImports.contains(it.substringBeforeLast('.')) }

    val psiFile = psiManager.findFile(
        LightVirtualFile(
            path.fileName.toString(),
            if (path.extension == "kt") KotlinFileType.INSTANCE else JavaFileType.INSTANCE,
            Files.readString(path)
        )
    ) ?: throw IllegalStateException()
    val exportedPackages = psiFile.children.mapNotNull { child ->
        when (child) {
            is KtClass -> inferKotlinExports(importedClassToPackages, child)
            is PsiClass -> inferExports(importedClassToPackages, child)
            else -> emptyList()
        }
    }.flatten()
        .filter { !it.startsWith("java.") }
        .filter { !defaultKotlinPackageImports.contains(it.substringBeforeLast('.')) }
    return JvmFile(path, packageFqName, importedPackagesFiltered.toSortedSet(), exportedPackages.toSortedSet())
}

fun inferKotlinExports(importedClassToPackages: Map<String, String>, clazz: KtClass): List<String> {
    val superNames = clazz.getSuperNames()
    val superPackages = superNames.mapNotNull { importedClassToPackages[it] }
    val typeNames = (clazz.children.lastOrNull()?.children ?: emptyArray()).mapNotNull {
        when(it) {
            is KtNamedFunction -> it.typeReference?.text?.substringBefore('<')
            is KtProperty -> it.typeReference?.text?.substringBefore('<')
            else -> null
        }
    }.mapNotNull { importedClassToPackages[it] }
    return superPackages + typeNames
}

fun inferExports(importedClassToPackages: Map<String, String>, clazz: PsiClass): List<String> {
    val extendsList = clazz.extendsList?.referencedTypes?.mapNotNull {
        it.className
    }?.mapNotNull { importedClassToPackages[it] } ?: emptyList<String>()
    val implementsList = clazz.implementsList?.referencedTypes?.mapNotNull {
        it.className
    }?.mapNotNull { importedClassToPackages[it] } ?: emptyList<String>()
    val methodsList = try {
        clazz.allMethods?.mapNotNull {
            it.returnType?.canonicalText
        }?.mapNotNull { importedClassToPackages[it] } ?: emptyList<String>()
    } catch (th: Throwable) {
        System.err.println("Failed to infer method return types for ${clazz.name}")
        emptyList<String>()
    }
    return (extendsList + implementsList + methodsList).toSortedSet().toList()
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
    val parts = line.substringBefore(" as").split(" ")

    val fqn = if (parts.size == 3) {
        if (parts[1] == "static") parts[2] else error("Invalid import statement: $line")
    } else if (parts.size == 2) {
        parts[1]
    } else {
        error("Invalid import statement: $line")
    }

    return fqn.removeSuffix(";")
}
