/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package build

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.RelativePath
import org.gradle.api.specs.Spec
import org.gradle.api.specs.Specs
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

import org.gradle.api.internal.file.archive.ZipCopyAction.CONSTANT_TIME_FOR_ZIP_ENTRIES
import org.gradle.api.internal.file.pattern.PatternMatcherFactory

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

import org.gradle.kotlin.dsl.*


@CacheableTask
@Suppress("unused")
abstract class PatchKotlinCompilerEmbeddable : DefaultTask() {

    @Input
    val excludes = project.objects.listProperty<String>()

    @get:Classpath
    abstract val originalFiles: ConfigurableFileCollection

    @get:Classpath
    abstract val dependencies: ConfigurableFileCollection

    @Input
    val dependenciesIncludes = project.objects.mapProperty<String, List<String>>()

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val additionalRootFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    @Suppress("unused")
    fun patchKotlinCompilerEmbeddable() =
        patchKotlinCompilerEmbeddable(
            originalFile = originalFiles.single {
                it.name.startsWith("kotlin-compiler-embeddable-")
            },
            patchedFile = outputFile.asFile.get().apply {
                delete()
                parentFile.mkdirs()
            }
        )

    private
    fun patchKotlinCompilerEmbeddable(originalFile: File, patchedFile: File) =
        ZipFile(originalFile).use { originalJar ->
            ZipOutputStream(patchedFile.outputStream().buffered()).use { patchedJar ->
                patch(originalJar, patchedJar)
            }
        }

    private
    fun patch(originalJar: ZipFile, patchedJar: ZipOutputStream) {
        patchedJar.setComment(originalJar.comment)
        copyFromOriginalApplyingExcludes(originalJar, patchedJar)
        copyFromDependenciesApplyingIncludes(patchedJar)
        copyAdditionalRootFiles(patchedJar)
    }

    private
    fun copyFromOriginalApplyingExcludes(originalJar: ZipFile, patchedJar: ZipOutputStream) =
        originalJar.entries().asSequence().filterExcluded().forEach { originalEntry ->
            copyEntry(originalJar, originalEntry, patchedJar)
        }

    private
    fun copyFromDependenciesApplyingIncludes(patchedJar: ZipOutputStream) =
        dependenciesIncludes.get().asSequence()
            .flatMap { (jarPrefix, includes) ->
                val includeSpec = includeSpecFor(includes)
                dependencies.asSequence().filter { it.name.startsWith(jarPrefix) }.map { it to includeSpec }
            }
            .forEach { (includedJarFile, includeSpec) ->
                ZipFile(includedJarFile).use { includedJar ->
                    includedJar.entries().asSequence()
                        .filter { !it.isDirectory && includeSpec.isSatisfiedBy(RelativePath.parse(true, it.name)) }
                        .forEach { includedEntry ->
                            copyEntry(includedJar, includedEntry, patchedJar)
                        }
                }
            }

    private
    fun copyAdditionalRootFiles(patchedJar: ZipOutputStream) =
        additionalRootFiles.forEach { additionalRootFile ->
            patchedJar.putNextEntry(ZipEntry(additionalRootFile.name).apply {
                time = CONSTANT_TIME_FOR_ZIP_ENTRIES
                size = additionalRootFile.length()
            })
            additionalRootFile.inputStream().buffered().use { input ->
                input.copyTo(patchedJar)
            }
            patchedJar.closeEntry()
        }

    private
    fun copyEntry(sourceJar: ZipFile, sourceEntry: ZipEntry, destinationJar: ZipOutputStream) {
        destinationJar.putNextEntry(ZipEntry(sourceEntry))
        sourceJar.getInputStream(sourceEntry).buffered().use { input ->
            input.copyTo(destinationJar)
        }
        destinationJar.closeEntry()
    }

    private
    fun Sequence<ZipEntry>.filterExcluded() =
        excludeSpecFor(excludes.get()).let { excludeSpec ->
            filter {
                excludeSpec.isSatisfiedBy(RelativePath.parse(true, it.name))
            }
        }

    private
    fun includeSpecFor(includes: List<String>): Spec<RelativePath> =
        patternSpecFor(includes)

    private
    fun excludeSpecFor(excludes: List<String>): Spec<RelativePath> =
        Specs.negate(patternSpecFor(excludes))

    private
    fun patternSpecFor(patterns: List<String>): Spec<RelativePath> =
        Specs.union(patterns.map {
            PatternMatcherFactory.getPatternMatcher(true, true, it)
        })
}
