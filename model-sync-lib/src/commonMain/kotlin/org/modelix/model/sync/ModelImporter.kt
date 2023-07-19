package org.modelix.model.sync

import org.modelix.model.api.*
import org.modelix.model.data.ModelData
import org.modelix.model.data.NodeData
import java.io.File

class ModelImporter(private val root: INode) {

    private val originalIdToExisting: MutableMap<String, INode> = mutableMapOf()
    private val originalIdToSpec: MutableMap<String, NodeData> = mutableMapOf()

    fun import(jsonFile: File) {
        require(jsonFile.exists())
        require(jsonFile.extension == "json")

        val data = ModelData.fromJson(jsonFile.readText())
        import(data)
    }
    
    fun import(data: ModelData) {
        originalIdToExisting.clear()
        originalIdToSpec.clear()

        syncProperties(root, data.root) // root original id is required for following operations
        buildSpecIndex(data.root)

        syncAllProperties(root.getDescendants(false))
        buildExistingIndex(root)

        sortAllExistingChildren(root)
        val addedNodes = addAllMissingChildren(root)
        syncAllProperties(addedNodes.asSequence())

        handleAllMovesAcrossParents(root)

        addedNodes.forEach {node ->
            node.originalId()?.let { originalIdToExisting[it] = node }
        }

        syncAllReferences(root)

        deleteAllExtraChildren(root)
    }

    private fun buildExistingIndex(root: INode) {
        root.getDescendants(true).forEach {node ->
            node.originalId()?.let { originalIdToExisting[it] = node }
        }
    }

    private fun buildSpecIndex(nodeData: NodeData) {
        nodeData.originalId()?.let { originalIdToSpec[it] = nodeData }
        nodeData.children.forEach { buildSpecIndex(it) }
    }

    private fun syncAllProperties(nodeSequence: Sequence<INode>) {
        nodeSequence.forEach {node ->
            originalIdToSpec[node.originalId()]?.let { spec -> syncProperties(node, spec) }
        }
    }

    private fun syncProperties(node: INode, nodeData: NodeData) {
        if (node.getPropertyValue(NodeData.idPropertyKey) == null) {
            node.setPropertyValue(NodeData.idPropertyKey, nodeData.originalId())
        }

        nodeData.properties.forEach {
            if (node.getPropertyValue(it.key) != it.value) {
                node.setPropertyValue(it.key, it.value)
            }
        }

        val toBeRemoved = node.getPropertyRoles().toSet()
            .subtract(nodeData.properties.keys)
            .filter { it != NodeData.idPropertyKey }
        toBeRemoved.forEach { node.setPropertyValue(it, null) }
    }

    private fun syncAllReferences(root: INode) {
        root.getDescendants(true).forEach {node ->
            originalIdToSpec[node.originalId()]?.let { spec -> syncReferences(node, spec) }
        }
    }

    private fun syncReferences(node: INode, nodeData: NodeData) {
        nodeData.references.forEach {
            if (node.getReferenceTargetRef(it.key) != originalIdToExisting[it.value]?.reference) {
                node.setReferenceTarget(it.key, originalIdToExisting[it.value]?.reference)
            }
        }
        val toBeRemoved = node.getReferenceRoles().toSet().subtract(nodeData.references.keys)
        toBeRemoved.forEach {
            val nullReference: INodeReference? = null
            node.setReferenceTarget(it, nullReference)
        }
    }

    private fun addAllMissingChildren(node: INode): List<INode> {
        val addedNodes = mutableListOf<INode>()
        originalIdToSpec[node.originalId()]?.let {
            addedNodes.addAll(
                addMissingChildren(node, it)
            )
        }
        node.allChildren.forEach {
            addedNodes.addAll(addAllMissingChildren(it))
        }
        return addedNodes
    }

    private fun addMissingChildren(node: INode, nodeData: NodeData): List<INode> {
        val specifiedChildren = nodeData.children.toList()
        val toBeAdded = specifiedChildren.filter { !originalIdToExisting.contains(it.originalId()) }

        return toBeAdded.map { nodeToBeAdded ->
            val childrenInRole = node.allChildren.filter { it.roleInParent == nodeToBeAdded.role }
            val existingIds = childrenInRole.map { it.originalId() }
            val baseIndex = nodeToBeAdded.getIndexWithinRole(nodeData)
            var offset = 0
            offset += childrenInRole.slice(0..minOf(baseIndex, childrenInRole.lastIndex)).count {
                !originalIdToSpec.containsKey(it.originalId()) // node will be deleted
            }
            offset -= specifiedChildren.filter { it.role == nodeToBeAdded.role }.slice(0 until baseIndex).count {
                !existingIds.contains(it.originalId()) // node will be moved here
            }
            val index = (baseIndex + offset).coerceIn(0..childrenInRole.size)
            val concept = nodeToBeAdded.concept?.let { s -> ConceptReference(s) }

            node.addNewChild(nodeToBeAdded.role, index, concept).apply {
                setPropertyValue(NodeData.idPropertyKey, nodeToBeAdded.originalId())
            }
        }
    }

    private fun sortAllExistingChildren(root: INode) {
        root.getDescendants(true).forEach { node ->
            originalIdToSpec[node.originalId()]?.let { sortExistingChildren(node, it) }
        }
    }

    private fun sortExistingChildren(node: INode, nodeData: NodeData) {
        val existingChildren = node.allChildren.toList()
        val existingIds = existingChildren.map { it.originalId() }
        val specifiedChildren = nodeData.children
        val toBeSortedSpec = specifiedChildren.filter { originalIdToExisting.containsKey(it.originalId()) }

        val targetIndices = HashMap<String?, Int>(nodeData.children.size)
        for (child in toBeSortedSpec) {
            val childrenInRole = existingChildren.filter { it.roleInParent == child.role }
            val baseIndex = child.getIndexWithinRole(nodeData)
            var offset = 0
            offset += childrenInRole.slice(0..baseIndex.coerceAtMost(childrenInRole.lastIndex)).count {
                !originalIdToSpec.containsKey(it.originalId()) // node will be deleted
            }
            offset -= specifiedChildren
                .filter { it.role == child.role }
                .slice(0..baseIndex.coerceIn(0..specifiedChildren.lastIndex))
                .count {
                    !existingIds.contains(it.originalId()) // node will be moved here
                }
            val index = (baseIndex + offset).coerceIn(0..childrenInRole.size)
            targetIndices[child.originalId()] = index
        }

        existingChildren.forEach { child ->
            val currentIndex = child.index()
            val targetRole = originalIdToSpec[child.originalId()]?.role
            val targetIndex = targetIndices[child.originalId()]
            if (targetIndex != null && (targetIndex != currentIndex || child.roleInParent != targetRole)) {
                node.moveChild(targetRole, targetIndex, child)
            }
        }
    }

    private fun handleAllMovesAcrossParents(root: INode) {
        val moves = collectMovesAcrossParents(root)
        while (moves.isNotEmpty()) {
            val nextMove = moves.first { !it.nodeToBeMoved.getDescendants(false).contains(it.targetParent) }
            performMoveAcrossParents(nextMove.targetParent, nextMove.nodeToBeMoved)
            moves.remove(nextMove)
        }
    }

    private fun collectMovesAcrossParents(root: INode): MutableList<MoveAcrossParents> {
        val movesAcrossParents = mutableListOf<MoveAcrossParents>()
        root.getDescendants(true).forEach {node ->
            val nodeData = originalIdToSpec[node.originalId()] ?: return@forEach

            val missingIds = nodeData.children.map { it.originalId() }.toSet()
                .subtract(node.allChildren.map { it.originalId() }.toSet())
            val toBeMovedHere = missingIds
                .filter { originalIdToSpec.containsKey(it) }
                .mapNotNull { originalIdToExisting[it] }

            toBeMovedHere.forEach {
                movesAcrossParents.add(MoveAcrossParents(node, it))
            }
        }
        return movesAcrossParents
    }

    private data class MoveAcrossParents(val targetParent: INode, val nodeToBeMoved: INode)

    private fun performMoveAcrossParents(node: INode, toBeMovedHere: INode) {
        val nodeData = originalIdToSpec[node.originalId()] ?: return
        val existingChildren = node.allChildren.toList()
        val spec = originalIdToSpec[toBeMovedHere.originalId()]!!
        val childrenInRole = existingChildren.filter { it.roleInParent == spec.role }
        val baseTargetIndex = spec.getIndexWithinRole(nodeData).coerceAtMost(childrenInRole.size)
        val offset = childrenInRole.slice(0 until  baseTargetIndex).count {
            !originalIdToSpec.containsKey(it.originalId()) // node will be deleted
        }
        val targetIndex = (baseTargetIndex + offset).coerceIn(0..childrenInRole.size)

        node.moveChild(spec.role, targetIndex, toBeMovedHere)

    }

    private fun deleteAllExtraChildren(root: INode) {
        val toBeRemoved = mutableListOf<INode>()
        root.allChildren.forEach {
            if (!originalIdToSpec.containsKey(it.originalId())) {
                toBeRemoved.add(it)
            }
        }
        toBeRemoved.forEach {
            it.parent?.removeChild(it)
        }
        root.allChildren.forEach {
            deleteAllExtraChildren(it)
        }
    }

    private fun NodeData.getIndexWithinRole(parent: NodeData) : Int =
        parent.children.filter { it.role == this.role }.indexOf(this)

}

internal fun INode.originalId(): String? {
    return this.getPropertyValue(NodeData.idPropertyKey)
}

internal fun NodeData.originalId(): String? {
    return properties[NodeData.idPropertyKey] ?: id
}