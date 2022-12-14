package org.modelix.metamodel.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.modelix.metamodel.generator.LanguageData
import org.modelix.metamodel.generator.LanguageSet
import org.modelix.metamodel.generator.MetaModelGenerator
import org.modelix.metamodel.generator.TypescriptMMGenerator
import java.io.File
import java.util.*
import javax.inject.Inject

abstract class GenerateMetaModelSources @Inject constructor(of: ObjectFactory) : DefaultTask() {
    @get:InputDirectory
    val exportedLanguagesDir: DirectoryProperty = of.directoryProperty()
    @get:OutputDirectory
    @Optional
    val kotlinOutputDir: DirectoryProperty = of.directoryProperty()
    @get:OutputDirectory
    @Optional
    val typescriptOutputDir: DirectoryProperty = of.directoryProperty()
    @get:Input
    val includedNamespaces: ListProperty<String> = of.listProperty(String::class.java)
    @get:Input
    val includedLanguages: ListProperty<String> = of.listProperty(String::class.java)
    @get:Input
    val includedConcepts: ListProperty<String> = of.listProperty(String::class.java)
    @get:Input
    @Optional
    val registrationHelperName: Property<String> = of.property(String::class.java)

    @TaskAction
    fun generate() {
        var languages: LanguageSet = LanguageSet(exportedLanguagesDir.get().asFile.walk()
            .filter { it.extension.lowercase() == "json" }
            .map { LanguageData.fromFile(it) }
            .toList())
        val previousLanguageCount = languages.getLanguages().size

        val includedNamespaces = this.includedNamespaces.get().map { it.trimEnd('.') }
        val includedLanguages = this.includedLanguages.get() + includedNamespaces
        val namespacePrefixes = includedNamespaces.map { it + "." }
        val includedConcepts = this.includedConcepts.get()

        languages = languages.filter {
            languages.getLanguages().filter { lang ->
                includedLanguages.contains(lang.name)
                        || namespacePrefixes.any { lang.name.startsWith(it) }
            }.forEach { lang ->
                lang.getConceptsInLanguage().forEach { concept ->
                    includeConcept(concept.fqName)
                }
            }
            includedConcepts.forEach { includeConcept(it) }
        }
        println("${languages.getLanguages().size} of $previousLanguageCount languages included")

        val kotlinOutputDir = this.kotlinOutputDir.orNull?.asFile
        if (kotlinOutputDir != null) {
            val generator = MetaModelGenerator(kotlinOutputDir.toPath())
            generator.generate(languages)
            registrationHelperName.orNull?.let {
                generator.generateRegistrationHelper(it, languages)
            }
        }

        val typescriptOutputDir = this.typescriptOutputDir.orNull?.asFile
        if (typescriptOutputDir != null) {
            val tsGenerator = TypescriptMMGenerator(typescriptOutputDir.toPath())
            tsGenerator.generate(languages)
        }
    }
}