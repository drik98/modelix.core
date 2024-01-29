package org.modelix.metamodel.generator

import com.squareup.kotlinpoet.ClassName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
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
            
            import {
                ChildListAccessor,
                IConceptJS,
                GeneratedConcept,
                INodeJS,
                SingleChildAccessor,
                ITypedNode,
            } from "@modelix/ts-model-api";
            import { shallowReactive, unref, MaybeRef, computed, customRef } from "vue";
            
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
                getChildConcepts: () => Map<string, IConceptJS>;
            }
            export interface IVuejsTypedNode extends ITypedNode {
                _node: INodeJS;
                _concept: IVuejsGeneratedConcept;
                _nextSibling?: INodeJS;
                _parent?: INodeJS;
            }
            
            export interface IVuejsChildArray<T extends ITypedNode> extends Array<T> {
            	length: number;
            	[n: number]: T;
            	createNew: (index?: number) => T;
            	move: ((fromIndex: number, toIndex: number) => T) | ((child: T, toIndex: number) => T);
            	delete: (child: T) => void;
            	toArray: () => Array<T>;
            	replaceAll: (newArray: T[]) => void;
            }

            class ChildArrayImpl<T extends ITypedNode> implements IVuejsChildArray<T> {
            	#containingNode: INodeJS;
            	#role: string;
            	#concept: IConceptJS;
            	[n: number]: T;
            
            	constructor(containingNode: INodeJS, role: string, concept: IConceptJS) {
            		this.#containingNode = containingNode;
            		this.#role = role;
            		this.#concept = concept;
            		// Proxies are the only mechanism for intercepting arbitrary property requests AFAIK
            		const handler: ProxyHandler<ChildArrayImpl<T>> = {
            			get: (target: ChildArrayImpl<T>, p: string | symbol) => {
            				if (typeof p === "string" && /^\d+${'$'}/.test(p)) {
            					return target.at(Number.parseInt(p));
            				}
            				return Reflect.get(target, p);
            			},
            			set: (target: ChildArrayImpl<T>, p: string | symbol, newValue: any): boolean => {
            				if (typeof p === "string" && /^\d+${'$'}/.test(p)) {
            					return target.set(Number.parseInt(p), newValue);
            				}
            				return Reflect.set(target, p, newValue);
            			},
            		};
            		return new Proxy<ChildArrayImpl<T>>(this, handler);
            	}
            	*[Symbol.iterator]() {
            		var yieldCount = 0;
            		for (var i = 0; i < this.length; i++) {
            			yieldCount++;
            			yield this.at(i);
            		}
            		return yieldCount;
            	}
            	// This shouldn't matter for us at all, it specifies how the with() statement should behave with regard
            	// to some Array properties, but with() isn't even allowed in strict mode anyway.
            	[Symbol.unscopables] = Array.prototype[Symbol.unscopables]
            
            	at(index: number): T {
            		return LanguageRegistry.INSTANCE.wrapNode(
            			this.#containingNode.getChildren(this.#role)[index]
            		) as T;
            	}
            	set(index: number, child: T): boolean {
            		this.#containingNode.moveChild(this.#role, index, child.unwrap());
            		return true;
            	}
            	push(...newItems: T[]): number {
            		for (var newChild of newItems) {
            			this.set(this.length, newChild);
            		}
            		return this.length;
            	}
            	get length(): number {
            		return this.#containingNode.getChildren(this.#role).length;
            	}
            	delete(child: T): void {
            		this.#containingNode.removeChild(child.unwrap());
            	}
            	move(childOrIndex: T | number, toIndex: number): T {
            		var movingChild: INodeJS;
            		if (typeof childOrIndex == "number") {
            			// it's an index
            			movingChild = this.#containingNode.getChildren(this.#role)[childOrIndex];
            		} else {
            			// it's a wrapped node
            			movingChild = childOrIndex.unwrap();
            		}
            		this.#containingNode.moveChild(this.#role, toIndex, movingChild);
            
            		return LanguageRegistry.INSTANCE.wrapNode(movingChild) as T;
            	}
            	toArray(): Array<T> {
            		return this.#containingNode
            			.getChildren(this.#role)
            			.map((child) => LanguageRegistry.INSTANCE.wrapNode(child) as T);
            	}
            	
            	createNew(index: number = -1, specifiedConcept: IConceptJS = this.#concept): T {
            		const newRawNode = this.#containingNode.addNewChild(this.#role, index, specifiedConcept);
            		return LanguageRegistry.INSTANCE.wrapNode(newRawNode) as T;
            	}
            	replaceAll(newArray: T[]): void {
            		for (var i = 0; i < newArray.length; i++) {
            			this.move(newArray[i], i);
            		}
            		for (var i = newArray.length; i < this.length; i++) {
            			this.delete(this.at(i));
            		}
            	}
            
            	// All of the remaining methods are trivial, and allow this to be treated as a normal JavaScript Array
            	// with all the bells and whistles. Each one just grabs the "real" method from Array.prototype and calls
            	// it on ourselves.
            
            	// Using the prototypes from Array *should* be safe, as they are set up to assume nothing about the array
            	// except that it has a proper this.length and that its elements are accessible like this[0], this[1], etc.
            
            	// https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Array#generic_array_methods
            
            	map<U>(callbackfn: (value: T, index: number, array: T[]) => U, thisArg?: any): U[] {
            		return Array.prototype.map.call(this, callbackfn) as U[]
            	}
                find<S extends T>(predicate: (value: T, index: number, obj: T[]) => value is S, thisArg?: any): S | undefined {
            		return Array.prototype.find.call(this, predicate)
            	}
            	pop(): T | undefined {
            		return Array.prototype.pop.call(this)
            	}
            	concat(...items: (T | ConcatArray<T>)[]): T[] {
            		return Array.prototype.concat.call(this)
            	}
                join(separator?: string): string {
            		return Array.prototype.join.call(this, separator)
            	}
            	reverse(): T[] {
            		return Array.prototype.reverse.call(this)
            	}
            	shift(): T | undefined {
            		return Array.prototype.shift.call(this)
            	}
            	slice(start?: number, end?: number): T[] {
            		return Array.prototype.slice.call(this, start, end)
            	}
            	sort(compareFn?: (a: T, b: T) => number): this {
            		Array.prototype.sort.call(this, compareFn)
            		return this
            	}
            	splice(start: number, deleteCount?: number): T[] {
            		return Array.prototype.splice.call(this, start, deleteCount)
            	}
            	unshift(...items: T[]): number {
            		return Array.prototype.unshift.call(this, items)
            	}
            	indexOf(searchElement: T, fromIndex?: number): number {
            		return Array.prototype.indexOf.call(this, searchElement, fromIndex)
            	}
            	lastIndexOf(searchElement: T, fromIndex?: number): number {
            		return Array.prototype.lastIndexOf.call(this, searchElement, fromIndex)
            	}
            	every<S extends T>(predicate: (value: T, index: number, array: T[]) => value is S, thisArg?: any): this is S[] {
            		return Array.prototype.every.call(this, predicate, thisArg)
            	}
            	some(predicate: (value: T, index: number, array: T[]) => unknown, thisArg?: any): boolean {
            		return Array.prototype.some.call(this, predicate)
            	}
            	forEach(callbackfn: (value: T, index: number, array: T[]) => void, thisArg?: any): void {
            		Array.prototype.forEach.call(this, callbackfn)
            	}
            	filter<S extends T>(predicate: (value: T, index: number, array: T[]) => value is S, thisArg?: any): S[] {
            		return Array.prototype.filter.call(this, predicate)
            	}
            	// I'm not going to go back and change the above Array.prototypes, but at this point I got stuck trying
            	// to make TypeScript happy with some of the more complex type definitions. It's not actually necessary
            	// to do these with proper types, though, since it's all going to be untyped at runtime anyway, and the
            	// enforcement of the interface is in `interface Array<T>`, not here. This should only cause issues if
            	// someone re-types this object as ChildArrayImpl directly and then passes in incompatible stuff.
            	reduce(callbackfn: any, initialValue?: any): any {
            		return Array.prototype.reduce.call(this, callbackfn, initialValue)
            	}
            	reduceRight(callbackfn: any, initialValue?: any): any {
            		return Array.prototype.reduceRight.call(this, callbackfn, initialValue)
            	}
            	findIndex(predicate: any, thisArg?: any): number {
            		return Array.prototype.findIndex.call(this, predicate, thisArg)
            	}
            	fill(value: T, start?: number, end?: number): this {
            		Array.prototype.fill.call(this, value, start, end)
            		return this
            	}
            	copyWithin(target: number, start: number, end?: number): this {
            		Array.prototype.copyWithin.call(this, target, start, end)
            		return this
            	}
            	entries(): IterableIterator<[number, T]> {
            		return Array.prototype.entries.call(this)
            	}
            	keys(): IterableIterator<number> {
            		return Array.prototype.keys.call(this)
            	}
            	values(): IterableIterator<T> {
            		return Array.prototype.values.call(this)
            	}
            	includes(searchElement: T, fromIndex?: number): boolean {
            		return Array.prototype.includes.call(this, searchElement, fromIndex)
            	}
            	flatMap( callback: any, thisArg?: any): any[] {
            		return Array.prototype.flatMap.call(this, callback, thisArg)
            	}
            	flat(thisArg: any, depth?: any) : any[] {
            		return Array.prototype.flat.call(thisArg, depth)
            	}
                findLast(predicate: (value: T, index: number, array: T[]) => unknown, thisArg?: any): T | undefined {
            		return Array.prototype.findLast.call(this, predicate, thisArg)
            	}
            	findLastIndex(predicate: (value: T, index: number, array: T[]) => unknown, thisArg?: any): number {
            		return Array.prototype.findLastIndex.call(this, predicate, thisArg)
            	}
            	toReversed(): T[] {
            		return Array.prototype.toReversed.call(this)
            	}
            	toSorted(compareFn?: (a: T, b: T) => number): T[] {
            		return Array.prototype.toSorted.call(this, compareFn)
            	}
            	toSpliced(...args: any[]): any[] {
            		// @ts-expect-error Types on this one are a bit odd too. Just take whatever we're given and pass it along.
            		return Array.prototype.toSpliced.apply(this, [...args])
            		// Technically, they could all just be done this way.
            	}
            	with(index: number, value: T): T[] {
            		return Array.prototype.with.call(this, index, value)
            	}
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
            
            function createProxy(
                concept: IVuejsGeneratedConcept,
                node: INodeJS
            ): IVuejsTypedNode {
                const refs = new Map();
                const triggers = new Map();
                const getComputedRefForProperty = (role: string, feature: RoleDecl) => {
                    const existingComputedRef = refs.get(role);
                    if (existingComputedRef !== undefined) {
                        return existingComputedRef;
                    }
                    const newComputedRef = customRef<string | number | boolean>(
                        (track, trigger) => {
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
                                set: (value) => {
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
                                },
                            };
                        }
                    );
                    refs.set(role, newComputedRef);
                    return newComputedRef;
                };
                const getComputedRefForChild = (role: string, feature: ChildLinkDecl) => {
                    const existingComputedRef = refs.get(role);
                    if (existingComputedRef !== undefined) {
                        return existingComputedRef;
                    }
		            if (feature.multiple) {
		            	// Contains multiple children for this role, so use our array-like wrapper.
		            	const newComputedRef = customRef<ChildArrayImpl<ITypedNode>>((track, trigger) => {
		            		triggers.set(role, trigger);
		            		const childArrayWrapper = new ChildArrayImpl<ITypedNode>(node, role, concept)
		            		return {
		            			get: () => {
		            				track();
		            				return childArrayWrapper;
		            			},
		            			set: (_value) => {
		            				childArrayWrapper.replaceAll(_value.toArray())
		            			},
		            		};
		            	});
		            	refs.set(role, newComputedRef);
		            	return newComputedRef;
		            } else {
		            	// Only one child in this role, so this is pretty easy
		            	const newComputedRef = customRef<ITypedNode>((track, trigger) => {
		            		triggers.set(role, trigger);
		            		return {
		            			get: () => {
		            				track();
		            				const childArray = node
		            					.getChildren(role)
		            					.map((child) => LanguageRegistry.INSTANCE.wrapNode(child));
		            				return childArray[0];
		            			},
		            			set: (_value) => {
		            				node.removeChild(node.getChildren(role)[0])
                                    if (_value != null) {
		            				    node.moveChild(role, 0, _value.unwrap())
                                    }
		            			},
		            		};
		            	});
		            	refs.set(role, newComputedRef);
		            	return newComputedRef;
		            }
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
                                return target
                                    ? LanguageRegistry.INSTANCE.wrapNode(target)
                                    : undefined;
                            },
                            set: (value) => {
                                const unwrappedValue = (
                                    value?.unwrap ? value.unwrap() : value
                                ) as INodeJS;
                                node.setReferenceTargetNode(role, unwrappedValue);
                            },
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
	            		if (/^__new_child_/.test(key)) {
	            			// Special synthetic method for instantiating a non-multiple child. (The multiple version of this
	            			// is in ChildArrayImpl.) Can be given an IConceptJS to use for adding the child, otherwise it
                            // uses the one that our own concept specifies.
				            return (specifiedChildConcept: IConceptJS) => {
				            	const role = key.replace("__new_child_", "");
				            	const childConcept = specifiedChildConcept ? specifiedChildConcept : concept.getChildConcepts().get(role);
				            	_node.addNewChild(role, 0, childConcept);
				            	return getComputedRefForChild(role, concept.features.get(role) as ChildLinkDecl).value;
				            }
	            		}
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
                                throw Error("Can't update child links yet");
                                break;
                            case "REFERENCE":
                                getComputedRefForRef(key, feature).value = value;
                                break;
                        }
                        isSettingLock.isSetting = false;
                        return true;
                    },
                    ownKeys(_node: INodeJS) {
                        return Array.from<string | symbol>(concept.features.keys()).concat(
                            "_node",
                            "_concept"
                        );
                    },
                    has(_node: INodeJS, key: string) {
                        return Array.from<string | symbol>(concept.features.keys())
                            .concat("_node", "_concept")
                            .includes(key);
                    },
                };
                return new Proxy<INodeJS, IVuejsTypedNode>(node, proxyHandler);
            }
            
            export function wrapNode(
                C_Concept: IVuejsGeneratedConcept,
                node: INodeJS
            ): IVuejsTypedNode {
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
            
            import { computed, WritableComputedRef, shallowReactive, Ref, unref, toRaw, triggerRef } from "vue";
            import { IVuejsGeneratedConcept, IVuejsTypedNode, IVuejsChildArray, wrapNode, RoleDecl } from "./index";
            
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
                public getConcepts() : Array<GeneratedConcept> {
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
                        set ${feature.generatedName}(value: $entityType)
                    """.trimIndent()
                }
                is ProcessedChildLink -> {
                    val N_InterfaceName = feature.type.resolved.tsInterfaceRef(concept.language)
                    if (feature.multiple) {
                        """
                        ${feature.generatedName}: IVuejsChildArray<$N_InterfaceName>
                        """.trimIndent()
                    } else {
                        """
                        ${feature.generatedName}: $N_InterfaceName;
                        __new_child_${feature.generatedName}: (specifiedChildConcept?: IConceptJS) => $N_InterfaceName;
                        """.trimIndent()
                    }
                }
                else -> ""
            }
        }
        val interfaceList = concept.getDirectSuperConcepts().joinToString(", ") { it.tsInterfaceRef(concept.language) }.ifEmpty { "IVuejsTypedNode" }
        // TODO extend first super concept do reduce the number of generated members
        val featureInterfaceType: String;
        if (concept.getDirectSuperConcepts().count() <= 1 && features.isEmpty()) {
            featureInterfaceType = "export type ${concept.nodeWrapperInterfaceName()} = $interfaceList;"
        } else {
            featureInterfaceType =
                """
                export interface ${concept.nodeWrapperInterfaceName()} extends $interfaceList {
                    ${features}
                }
                """.trimIndent()
        }


        return """
            
            export class ${concept.conceptWrapperImplName()} extends GeneratedConcept implements IVuejsGeneratedConcept {
              constructor(uid: string) {
                super(uid);
              }
              getDirectSuperConcepts(): Array<IConceptJS> {
                return [${concept.getDirectSuperConcepts().joinToString(",") { it.languagePrefix(concept.language) + it.conceptWrapperInterfaceName() }}];
              }
              getChildConcepts(): Map<string, IConceptJS> {
                const theMap = new Map<string, IConceptJS>()
                ${concept.getAllSuperConcepts().flatMap { it.getOwnRoles() }.filterIsInstance<ProcessedChildLink>()
                        .joinToString("\n") { "theMap.set(\"${it.generatedName}\", ${it.concept.languagePrefix(concept.language) + it.concept.conceptWrapperInterfaceName()})" }}
                return theMap
              }
              get fqName() {
                return "${concept.fqName()}";
              }
              features = new Map<string, RoleDecl>([ ${concept.getAllSuperConceptsAndSelf().flatMap { it.getOwnRoles() }.map { """
                  ["${it.generatedName}", ${it.serialize()}]
                  """.trimIndent()}.joinToString(", ") }])
            }
            export const ${concept.conceptWrapperInterfaceName()} = new ${concept.conceptWrapperImplName()}("${concept.uid}")
            
            $featureInterfaceType
            
            export function isOfConcept_${concept.name}(node: IVuejsTypedNode | Ref<IVuejsTypedNode>): node is ${concept.nodeWrapperInterfaceName()} {
                return ${concept.conceptWrapperInterfaceName()} === toRaw(unref(node)._concept);
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
