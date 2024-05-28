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
            export * from "./proxy";

            export function registerLanguages() {
                ${languages.getLanguages().joinToString("\n") { """
                    LanguageRegistry.INSTANCE.register(${it.simpleClassName()}.INSTANCE);
                """.trimIndent() }}
            }
            """.trimIndent())
           
        outputDir.resolve("proxy.ts").writeText("""
            import {
                IConceptJS,
                INodeJS,
                ITypedNode,
            LanguageRegistry,
            } from "@modelix/ts-model-api";
            import { unref, MaybeRef, customRef } from "vue";

            export type ChildLinkDecl = {
                kind: "CHILD";
                type: string;
                optional: boolean;
                multiple: boolean;
            };
            export type ReferenceLinkDecl = {
                kind: "REFERENCE";
                type: string;
                optional: boolean;
            };
            export type PropertyDecl = {
                kind: "PROPERTY";
                type: string;
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
                _parent?: INodeJS;
                remove: () => {};
            }

            type INodeReferenceJS = any;
            const wrappedNodeCache = new Map<INodeReferenceJS, IVuejsTypedNode>();
            // https://stackoverflow.com/a/68488123
            const combine = function* (...iterators: any[]) {
                for (const it of iterators) yield* it;
            };

            // satisfy typechecker as in https://stackoverflow.com/a/50603826
            declare global {
                interface ProxyConstructor {
                    new <TSource extends object, TTarget extends object>(
                        target: TSource,
                        handler: ProxyHandler<TSource>
                    ): TTarget;
                }
            }

            export const isSettingLock = {
                isSetting: false,
            };

            export type ChangeCallback = (value: any) => void;
            export type ChangeHandler = (updateWrappedNode: ChangeCallback) => ChangeCallback;

            // by default, just call the callback
            var changeHandler: ChangeHandler = (updateWrappedNode: ChangeCallback) =>  (value: any) => updateWrappedNode(value);

            /**
            * Allows to dynamically decide if a change should be applied to the wrapped node or not.
            * Also allows to make some changes to the value before storing it.
            *
            * @param newChangeHandler the handler which is deciding if updating the wrapped node and with what value
            */
            export function setModelChangeHandler(newChangeHandler: ChangeHandler) {
            changeHandler = newChangeHandler
            }

            function createProxy(concept: IVuejsGeneratedConcept, node: INodeJS): IVuejsTypedNode {
                const refs = new Map();
                const triggers = new Map();
                const getComputedRefForProperty = (role: string, feature: RoleDecl) => {
                    const existingComputedRef = refs.get(role);
                    if (existingComputedRef !== undefined) {
                        return existingComputedRef;
                    }
                    const newComputedRef = customRef<string | number | boolean>((track, trigger) => {
                        triggers.set(role, trigger);
                        return {
                            get: () => {
                                track();
                                const raw = node.getPropertyValue(role);
                                switch (feature.type) {
                                    case "INT":
                                        return raw ? parseInt(raw!) : 0;
                                    case "BOOLEAN":
                                        return raw === "true";
                                    case "STRING":
                                        return raw ?? "";
                                    default:
                                        return ""; // enum
                                }
                            },
                            set: changeHandler((value) => {
                                switch (feature.type) {
                                    case "INT":
                                        node.setPropertyValue(role, value.toString());
                                        break;
                                    case "BOOLEAN":
                                        node.setPropertyValue(role, value ? "true" : "false");
                                        break;
                                    case "STRING":
                                        // TODO Olekz type checking etc.
                                        node.setPropertyValue(role, value as string);
                                        break;
                                    default:
                                        throw new Error("Unknown property type"); // enum
                                }
                            }),
                        };
                    });
                    refs.set(role, newComputedRef);
                    return newComputedRef;
                };
                const getComputedRefForChild = (role: string, feature: ChildLinkDecl) => {
                    const existingComputedRef = refs.get(role);
                    if (existingComputedRef !== undefined) {
                        return existingComputedRef;
                    }
                    const newComputedRef = customRef<ITypedNode | ITypedNode[]>((track, trigger) => {
                        triggers.set(role, trigger);
                        return {
                            get: () => {
                                track();
                                const childArray = node
                                    .getChildren(role)
                                    .map((child) => LanguageRegistry.INSTANCE.wrapNode(child));
                                if (feature.multiple) {
                                    // Object.freeze(childArray);
                                    return childArray;
                                } else {
                                    // return childArray;
                                    return childArray[0];
                                }
                            },
                            set: changeHandler((_value) => {
                                throw "can not set child";
                            }),
                        };
                    });
                    refs.set(role, newComputedRef);
                    return newComputedRef;
                };
                const getComputedRefForRef = (role: string, feature: RoleDecl) => {
                    const existingComputedRef = refs.get(role);
                    if (existingComputedRef !== undefined) {
                        return existingComputedRef;
                    }
                    const newComputedRef = customRef<ITypedNode>((track, trigger) => {
                        triggers.set(role, trigger);
                        return {
                            get: () => {
                                track();
                                const target = node.getReferenceTargetNode(role);
                                return target ? LanguageRegistry.INSTANCE.wrapNode(target) : undefined;
                            },
                            set: changeHandler((value) => {
                                const unwrappedValue = (value?.unwrap ? value.unwrap() : value) as INodeJS;
                                node.setReferenceTargetNode(role, unwrappedValue);
                            }),
                        };
                    });
                    refs.set(role, newComputedRef);
                    return newComputedRef;
                };
                const proxyHandler: ProxyHandler<INodeJS> = {
                    get(_node: INodeJS, keyOrSymbol: string | symbol) {
                        switch (keyOrSymbol) {
                            // case Symbol.toStringTag: return C_Concept.fqName; break; // breaks reactivity for some reason
                            case "_refs":
                                return refs;
                            case "_triggers":
                                return triggers;
                            case "_node":
                                return _node;
                                break;
                            case "remove":
                                return () => _node.getParent()?.removeChild(_node);
                            case "unwrap":
                                return () => _node;
                                break;
                            case "_concept":
                                return concept;
                                break;
                            case "_parent":
                                return _node.getParent();
                                break;
                            case "_nextSibling": {
                                const allSiblings = _node.getParent()?.getChildren(_node.getRoleInParent());
                                if (allSiblings === undefined) return undefined;
                                const index = allSiblings.map((c) => c.getReference()).indexOf(_node.getReference());
                                return allSiblings[(index + 1) % allSiblings.length]; // return next one and start from top when overflowing
                                break;
                            }
                        }
                        const key = keyOrSymbol as string;
                        const feature = concept.features.get(key);
                        if (!feature) return undefined;
                        switch (feature.kind) {
                            case "PROPERTY":
                                return getComputedRefForProperty(key, feature).value;
                            case "CHILD":
                                return getComputedRefForChild(key, feature).value;
                            case "REFERENCE":
                                return getComputedRefForRef(key, feature).value;
                        }
                    },
                    set(_node: INodeJS, key: string, maybeRefValue: MaybeRef<INodeJS | any>) {
                        isSettingLock.isSetting = false;
                        const feature = concept.features.get(key);
                        if (!feature) return false;
                        const value = unref(maybeRefValue);
                        switch (feature.kind) {
                            case "PROPERTY":
                                getComputedRefForProperty(key, feature).value = value;
                                break;
                            case "CHILD":
                                throw new Error("Can't update child links yet");
                                break;
                            case "REFERENCE":
                                getComputedRefForRef(key, feature).value = value;
                                break;
                        }
                        isSettingLock.isSetting = false;
                        return true;
                    },
                    ownKeys(_node: INodeJS) {
                        return Array.from<string | symbol>(concept.features.keys()).concat("_node", "_concept");
                    },
                    has(_node: INodeJS, key: string) {
                        return Array.from<string | symbol>(concept.features.keys())
                            .concat("_node", "_concept")
                            .includes(key);
                    },
                };
                return new Proxy<INodeJS, IVuejsTypedNode>(node, proxyHandler);
            }

            export function wrapNode(C_Concept: IVuejsGeneratedConcept, node: INodeJS): IVuejsTypedNode {
                const ref = node.getReference();
                if (!wrappedNodeCache.has(ref)) {
                    wrappedNodeCache.set(ref, createProxy(C_Concept, node));
                }
                return wrappedNodeCache.get(ref)!;
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
            
            import { computed, WritableComputedRef, shallowReactive, Ref, MaybeRef, unref, triggerRef } from "vue";
            import { IVuejsGeneratedConcept, IVuejsTypedNode, RoleDecl, wrapNode } from "./proxy";
            
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
                        set ${feature.generatedName}(value: $entityType | undefined);
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
            
            export function isOfConcept_${concept.name}(node: MaybeRef<IVuejsTypedNode | null | undefined>): node is MaybeRef<${concept.nodeWrapperInterfaceName()}> {
                return ${concept.conceptWrapperInterfaceName()} === toRaw(unref(node).?_concept);
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
