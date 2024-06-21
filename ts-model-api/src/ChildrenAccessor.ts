import type {ITypedNode} from "./TypedNode.js";
import type {INodeJS} from "./INodeJS.js";
import {LanguageRegistry} from "./LanguageRegistry.js";
import type {IConceptJS} from "./IConceptJS.js";

export abstract class ChildrenAccessor<ChildT extends ITypedNode> implements Iterable<ChildT> {
  constructor(public parentNode: INodeJS, public role: string | undefined, public concept?: IConceptJS | undefined) {
  }

  [Symbol.iterator](): Iterator<ChildT> {
    return this.parentNode.getChildren(this.role).map(n => this.wrapChild(n))[Symbol.iterator]();
  }

  public asArray(): Array<ChildT> {
    return this.parentNode.getChildren(this.role).map(n => this.wrapChild(n))
  }

  protected wrapChild(child: INodeJS): ChildT {
    return LanguageRegistry.INSTANCE.wrapNode(child) as ChildT
  }
}

export class ChildListAccessor<ChildT extends ITypedNode> extends ChildrenAccessor<ChildT> {
  constructor(parentNode: INodeJS, role: string | undefined, concept?: IConceptJS | undefined) {
    super(parentNode, role, concept);
  }

  /**
   * Adds a new element at the specified index to the list
   * @param subconcept The concept of the child which will be created.
   *   Should be a concept extending the base concept. If it is not a runtime error will be thrown.
   *   If not provided the base concept of the child accessor will be used if possible.
   * @returns the newly created child note
   */
  public insertNew(index: number, subconcept?: IConceptJS | undefined): ChildT {
    if(!verifySubConcept(subconcept, this.concept)) {
      throw new Error(`The provided subconcept "${subconcept?.getUID()}" cannot be applied to the base concept "${this.concept?.getUID()}".`)
    }

    return LanguageRegistry.INSTANCE.wrapNode(this.parentNode.addNewChild(this.role, index, subconcept ?? this.concept)) as ChildT
  }

  /**
   * Adds a new element to the end of the list
   * @param subconcept The concept of the child which will be created.
   *   Should be a concept extending the base concept. If it is not a runtime error will be thrown.
   *   If not provided the base concept of the child accessor will be used if possible.
   * @returns the newly created child note
   */
  public addNew(subconcept?: IConceptJS | undefined): ChildT {
    return this.insertNew(-1, subconcept)
  }
}

export class SingleChildAccessor<ChildT extends ITypedNode> extends ChildrenAccessor<ChildT> {
  constructor(parentNode: INodeJS, role: string | undefined, concept?: IConceptJS | undefined) {
    super(parentNode, role, concept);
  }

  public get(): ChildT | undefined {
    const children = this.asArray()
    return children.length === 0 ? undefined : children[0]
  }

  /**
   * Creates a new child node. Removes the previous one if it exists.
   * @param subconcept The concept of the child which will be created.
   *   Should be a concept extending the base concept. If it is not a runtime error will be thrown.
   *   If not provided the base concept of the child accessor will be used if possible.
   * @returns the newly created child note
   */
  public setNew(subconcept?: IConceptJS | undefined): ChildT {
    if(!verifySubConcept(subconcept, this.concept)) {
      throw new Error(`The provided subconcept "${subconcept?.getUID()}" cannot be applied to the base concept "${this.concept?.getUID()}".`)
    }

    const existing = this.get();
    if (existing !== undefined) {
      existing.remove();
    }
    return this.wrapChild(this.parentNode.addNewChild(this.role, 0, subconcept ?? this.concept))
  }
}

/**
 * Checks whether the provided sub concept is an extension of the base concept
 * @param subconcept A concept which should extend the base concept
 * @param baseConcept The base concept for the child accessor notes
 * @returns whether the provided sub concept is not not an extension of the base concept
 */
function verifySubConcept(
  subconcept: IConceptJS | undefined,
  concept: IConceptJS | undefined
): boolean {
  if (!subconcept || !concept) {
    return true;
  }
  return (
    subconcept.getUID() === concept.getUID() ||
    subconcept
      .getDirectSuperConcepts()
      .some((superConcept) => verifySubConcept(superConcept, concept))
  );
}
