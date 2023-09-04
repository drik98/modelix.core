package org.modelix.metamodel.generator

import com.squareup.kotlinpoet.ClassName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.modelix.model.data.LanguageData
import org.modelix.model.data.Primitive
import org.modelix.model.data.PrimitivePropertyType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class VuejsMMGenerator(val outputDir: Path, val nameConfig: NameConfig = NameConfig()) {

    private fun LanguageData.packageDir(): Path {
        val packageName = name
        var packageDir = outputDir
        if (packageName.isNotEmpty()) {
            for (packageComponent in packageName.split('.').dropLastWhile { it.isEmpty() }) {
                packageDir = packageDir.resolve(packageComponent)
            }
        }
        return packageDir
    }

    fun generate(languages: IProcessedLanguageSet) {
        generate(languages as ProcessedLanguageSet)
    }

    internal fun generate(languages: ProcessedLanguageSet) {
        Files.createDirectories(outputDir)
        for (language in languages.getLanguages()) {
            // TODO delete old files from previous generation
            outputDir
                .resolve(language.generatedClassName().simpleName + ".ts")
                .writeText(generateLanguage(language))

            generateRegistry(languages)
        }
    }

    private fun generateRegistry(languages: ProcessedLanguageSet) {
        outputDir.resolve("index.ts").writeText("""
            import { LanguageRegistry } from "@modelix/ts-model-api";
            ${languages.getLanguages().joinToString("\n") { """
                import { ${it.simpleClassName()} } from "./${it.simpleClassName()}";
            """.trimIndent() }}
            export function registerLanguages() {
                ${languages.getLanguages().joinToString("\n") { """
                    LanguageRegistry.INSTANCE.register(${it.simpleClassName()}.INSTANCE);
                """.trimIndent() }}
            }
            
            import { ChildListAccessor, IConceptJS, GeneratedConcept, INodeJS, SingleChildAccessor, ITypedNode } from "@modelix/ts-model-api";
            import { shallowReactive, unref, MaybeRef } from "vue";
            
            export type ChildLinkDecl = {
                kind: "CHILD",
                type: string,
                optional: boolean,
                multiple: boolean
            };
            export type ReferenceLinkDecl = {
                kind: "REFERENCE",
                type: string,
                optional: Boolean
            };
            export type PropertyDecl = {
                kind: "PROPERTY",
                type: string
            };
            export type RoleDecl = ChildLinkDecl | ReferenceLinkDecl | PropertyDecl;
            export interface IVuejsGeneratedConcept extends IConceptJS {
                get fqName(): string;
                features: Map<string, RoleDecl>;
            }
            export interface IVuejsTypedNode extends ITypedNode {
                _node: INodeJS;
                _concept: IVuejsGeneratedConcept;
                _nextSibling?: INodeJS;
            }
  
            type INodeReferenceJS = any
            const wrappedNodeCache = new Map<INodeReferenceJS, IVuejsTypedNode>();

            // https://stackoverflow.com/a/68488123
            let combine = function*(...iterators: any[]) {
              for (let it of iterators) yield* it;
            };
            
            // satisfy typechecker as in https://stackoverflow.com/a/50603826
            declare global  {
                interface ProxyConstructor {
                    new <TSource extends object, TTarget extends object>(target: TSource, handler: ProxyHandler<TSource>): TTarget;
                }
            }

            function createProxy(C_Concept: IVuejsGeneratedConcept, node: INodeJS): IVuejsTypedNode {
                const proxyHandler: ProxyHandler<INodeJS> = {
                    get(_node: INodeJS, keyOrSymbol: string | symbol) {
                        switch(keyOrSymbol) {
                            // case Symbol.toStringTag: return C_Concept.fqName; break; // breaks reactivity for some reason
                            case "_node": return _node; break;
                            case "unwrap": return () => _node; break;
                            case "_concept": return C_Concept; break;
                            case "_parent": return _node.getParent(); break;
                            case "_nextSibling": 
                                const allSiblings = _node.getParent()?.getChildren(_node.getRoleInParent());
                                if(allSiblings === undefined) return undefined;
                                const index = allSiblings.map((c) => c.getReference()).indexOf(_node.getReference())
                                return allSiblings[index+1 % allSiblings.length]; // return next one and start from top when overflowing
                                break;
                        }
                        const key = keyOrSymbol as string
                        const feature = C_Concept.features.get(key)
                        if(!feature) return undefined
                        switch(feature.kind) {
                            case "PROPERTY":
                                const raw = _node.getPropertyValue(key)
                                switch(feature.type) {
                                    case "INT": return raw ? parseInt(raw!!) : 0
                                    case "BOOLEAN": return raw === "true"
                                    case "STRING": return raw ?? ""
                                    default: return "" // enum
                                }
                            case "CHILD":
                                const childArray = _node.getChildren(key).map((child) => LanguageRegistry.INSTANCE.wrapNode(child))
                                if(feature.multiple) {
                                    return childArray;
                                } else {
                                    return childArray[0];
                                }
                            case "REFERENCE":
                                let target = _node.getReferenceTargetNode(key);
                                return target ? LanguageRegistry.INSTANCE.wrapNode(target) : undefined;
                        }
                    },
                    set(_node: INodeJS, key: string, maybeRefValue: MaybeRef<INodeJS | any>) {
                        const feature = C_Concept.features.get(key)
                        if(!feature) return false
                        const value = unref(maybeRefValue)
                        switch(feature.kind) {
                            case "PROPERTY":
                                switch(feature.type) {
                                    case "INT": _node.setPropertyValue(key, value.toString()); break;
                                    case "BOOLEAN": _node.setPropertyValue(key, value ? "true" : "false"); break;
                                    case "STRING": _node.setPropertyValue(key, value); break;
                                    default: throw new Error("Unknown property type") // enum
                                };
                                break;
                            case "CHILD":
                                throw Error("Can't update child links yet");
                                break;
                            case "REFERENCE":
                                const unwrappedValue = value?.unwrap ? value.unwrap() : value;
                                _node.setReferenceTargetNode(key, unwrappedValue);
                                break;
                        }
                        return true;
                    },
                    ownKeys(_node: INodeJS) {
                        return Array.from<string | symbol>(C_Concept.features.keys()).concat("_node", "_concept")
                    },
                    has(_node: INodeJS, key: string) {
                        return Array.from<string | symbol>(C_Concept.features.keys()).concat("_node", "_concept").includes(key)
                    }
                }
                return shallowReactive(new Proxy<INodeJS, IVuejsTypedNode>(node, proxyHandler))
            }
            
            export function wrapNode(C_Concept: IVuejsGeneratedConcept, node: INodeJS): IVuejsTypedNode {
                const ref = node.getReference()
                if(!wrappedNodeCache.has(ref)) {
                    wrappedNodeCache.set(ref, shallowReactive(createProxy(C_Concept, node)))
                }
                console.log("wrappedNodeCache has", wrappedNodeCache.size, "elements");
                return wrappedNodeCache.get(ref)!
            }
            
        """.trimIndent())
    }

    private fun generateLanguage(language: ProcessedLanguage): String {
        val conceptNamesList = language.getConcepts()
            .joinToString(", ") { it.conceptWrapperInterfaceName() }
        return """
            import {
                GeneratedConcept, 
                GeneratedLanguage,
                IConceptJS,
                INodeJS,
                ITypedNode, 
                TypedNode,
                LanguageRegistry
            } from "@modelix/ts-model-api";
            
            import { computed, WritableComputedRef, shallowReactive, Ref, unref, triggerRef } from "vue";
            import { IVuejsGeneratedConcept, IVuejsTypedNode, wrapNode, RoleDecl } from "./index";
            
            function triggerRefValue(ref: Ref, value: any) {
                return triggerRef(ref)
            }
            
            ${language.languageDependencies().joinToString("\n") {
            """import * as ${it.simpleClassName()} from "./${it.simpleClassName()}";"""
        }}
            
            ${language.getConcepts().joinToString("\n") { generateConcept(it) }.replaceIndent("            ")}
            
            export class ${language.simpleClassName()} extends GeneratedLanguage {
                public static INSTANCE: ${language.simpleClassName()} = new ${language.simpleClassName()}();
                constructor() {
                    super("${language.name}")
                    
                    ${language.getConcepts().joinToString("\n") { concept -> """
                        this.nodeWrappers.set("${concept.uid}", wrapNode.bind(this, ${concept.conceptWrapperInterfaceName()}))
                    """.trimIndent() }}
                }
                public getConcepts() {
                    return [$conceptNamesList]
                }
            }
        """.trimIndent()

    }

    private fun generateConcept(concept: ProcessedConcept): String {
        val features = concept.getOwnRoles().joinToString("\n") { feature ->
            when (feature) {
                is ProcessedProperty -> {
                    if (feature.type is PrimitivePropertyType) {
                        when ((feature.type as PrimitivePropertyType).primitive) {
                            Primitive.BOOLEAN -> {
                                """
                                ${feature.generatedName}: boolean;
                                
                                """.trimIndent()
                            }
                            Primitive.INT -> {
                                """
                                ${feature.generatedName}: number;
                                
                                """.trimIndent()
                            }
                            Primitive.STRING -> {
                                """
                                ${feature.generatedName}: string;
                                
                                """.trimIndent()
                            }
                        }
                    } else ""
                }
                is ProcessedReferenceLink -> {
                    val typeRef = feature.type.resolved
                    val languagePrefix = typeRef.languagePrefix(concept.language)
                    val entityType = "$languagePrefix${typeRef.nodeWrapperInterfaceName()}"
                    """
                        get ${feature.generatedName}(): $entityType | undefined;
                    """.trimIndent()
                }
                is ProcessedChildLink -> {
                    val N_InterfaceName = feature.type.resolved.tsInterfaceRef(concept.language)
                    """
                        ${feature.generatedName}: ${if (feature.multiple) "Array<$N_InterfaceName>" else N_InterfaceName }
                    """.trimIndent()
                }
                else -> ""
            }
        }
        val interfaceList = concept.getDirectSuperConcepts().joinToString(", ") { it.tsInterfaceRef(concept.language) }.ifEmpty { "IVuejsTypedNode" }
        // TODO extend first super concept do reduce the number of generated members
        return """
            
            export class ${concept.conceptWrapperImplName()} extends GeneratedConcept implements IVuejsGeneratedConcept {
              constructor(uid: string) {
                super(uid);
              }
              getDirectSuperConcepts(): Array<IConceptJS> {
                return [${concept.getDirectSuperConcepts().joinToString(",") { it.languagePrefix(concept.language) + it.conceptWrapperInterfaceName() }}];
              }
              get fqName() {
                return "${concept.fqName()}";
              }
              features = new Map<string, RoleDecl>([ ${concept.getAllSuperConceptsAndSelf().flatMap { it.getOwnRoles() }.map { """
                  ["${it.generatedName}", ${it.serialize()}]
                  """.trimIndent()}.joinToString(", ") }])
            }
            export const ${concept.conceptWrapperInterfaceName()} = new ${concept.conceptWrapperImplName()}("${concept.uid}")
            
            export interface ${concept.nodeWrapperInterfaceName()} extends $interfaceList {
                ${features}
            }
            
            export function isOfConcept_${concept.name}(node: IVuejsTypedNode | Ref<IVuejsTypedNode>): node is ${concept.nodeWrapperInterfaceName()} {
                return ${concept.conceptWrapperInterfaceName()} === unref(node)._concept;
            }
            
        """.trimIndent()
    }

    private fun ProcessedConcept.nodeWrapperInterfaceName() =
        nameConfig.typedNode(this.name)

    private fun ProcessedConcept.conceptWrapperImplName() =
        nameConfig.typedConceptImpl(this.name)

    private fun ProcessedConcept.nodeWrapperImplName() =
        nameConfig.typedNodeImpl(this.name)

    private fun ProcessedConcept.conceptWrapperInterfaceName() =
        nameConfig.typedConcept(this.name)

    private fun ProcessedLanguage.generatedClassName() =
        ClassName(name, nameConfig.languageClass(name))

    private fun ProcessedLanguage.simpleClassName() =
        this.generatedClassName().simpleName

    private fun ProcessedConcept.markerPropertyName() = "_is_" + this.fqName().replace(".", "_")
    //private fun ProcessedConcept.tsClassName() = nameConfig.languageClassName(this.language.name) + "." + this.name
    private fun ProcessedConcept.tsInterfaceRef(contextLanguage: ProcessedLanguage) = languagePrefix(contextLanguage) + nodeWrapperInterfaceName()
    private fun ProcessedConcept.languagePrefix(contextLanguage: ProcessedLanguage): String {
        return if (this.language == contextLanguage) {
            ""
        } else {
            nameConfig.languageClass(this.language.name) + "."
        }
    }

    private fun ProcessedRole.serialize() = Json.encodeToString(when(this) {
        is ProcessedChildLink -> JsonObject(mapOf(
            "kind" to JsonPrimitive("CHILD"),
            "type" to JsonPrimitive(type.name),
            "multiple" to JsonPrimitive(multiple),
            "optional" to JsonPrimitive(optional)
        ))
        is ProcessedReferenceLink -> JsonObject(mapOf(
            "kind" to JsonPrimitive("REFERENCE"),
            "type" to JsonPrimitive(type.name),
            "optional" to JsonPrimitive(optional)
        ))
        is ProcessedProperty -> JsonObject(mapOf(
            "kind" to JsonPrimitive("PROPERTY"),
            "type" to Json.encodeToJsonElement(this.type)
        ))
    })
}

private fun ProcessedProperty.rawValueName() = "raw_$generatedName"
