/*
 * Copyright (c) 2023.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.modelix.model.client2

import GeneratedConcept
import org.modelix.kotlin.utils.UnstableModelixFeature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(UnstableModelixFeature::class)
class BranchJSTest {

    private val emptyRoot = """
        {
            "root": {
            }
        }
    """.trimIndent()

    private val rootWithChild = """
        {
            "root": {
                "children": [
                    {
                        "id": "aNode"
                    }
                ]
            }
        }
    """.trimIndent()

    @Test
    fun canResolveNode() {
        // Arrange
        val branch = loadModelsFromJsonAsBranch(arrayOf(rootWithChild))
        val aNode = branch.rootNode.getAllChildren()[0]
        val aNodeReference = aNode.getReference()

        // Act
        val resolvedNode = branch.resolveNode(aNodeReference)

        // Assert
        assertEquals(aNode, resolvedNode)
    }

    @Test
    fun canResolveNodeNonExistingNode() {
        // Arrange
        val data = """
        {
            "root": {
                "children": [
                    {
                        "id": "aNode"
                    }
                ]
            }
        }
        """.trimIndent()
        val branch = loadModelsFromJsonAsBranch(arrayOf(data))
        val aNode = branch.rootNode.getAllChildren()[0]
        val aNodeReference = aNode.getReference()
        branch.rootNode.removeChild(aNode)

        // Act
        val resolvedNode = branch.resolveNode(aNodeReference)

        // Assert
        assertNull(resolvedNode)
    }

    @Test
    fun changeHandlerCanBeAdded() {
        val branch = loadModelsFromJsonAsBranch(arrayOf(rootWithChild))
        var changeCount = 0
        val changeListener: ChangeHandler = { _ -> changeCount++ }
        branch.addListener(changeListener)

        val aNode = branch.rootNode.getAllChildren()[0]
        branch.rootNode.removeChild(aNode)

        assertEquals(1, changeCount)
    }

    @Test
    fun changeHandlerCanBeRemoved() {
        val branch = loadModelsFromJsonAsBranch(arrayOf(rootWithChild))
        var changeCount = 0
        val changeListener: ChangeHandler = { _ -> changeCount++ }
        branch.addListener(changeListener)
        branch.removeListener(changeListener)

        val aNode = branch.rootNode.getAllChildren()[0]
        branch.rootNode.removeChild(aNode)

        assertEquals(0, changeCount)
    }

    @Test
    fun changeDetectionWorksForPropertyUpdate() {
        // Arrange
        var propertyChanged = 0
        val branch = loadModelsFromJsonAsBranch(arrayOf(emptyRoot))
        branch.addListener {
            when (it) {
                is PropertyChanged -> propertyChanged++
                else -> {}
            }
        }
        val rootNode = branch.rootNode

        // Act
        rootNode.setPropertyValue("aProperty", "aValue")

        // Assert
        assertEquals(1, propertyChanged)
    }

    @Test
    fun changeDetectionWorksForReferenceUpdate() {
        // Arrange
        var referenceChanged = 0
        val branch = loadModelsFromJsonAsBranch(arrayOf(emptyRoot))
        branch.addListener {
            when (it) {
                is ReferenceChanged -> referenceChanged++
                else -> {}
            }
        }
        val rootNode = branch.rootNode

        // Act
        rootNode.setReferenceTargetNode("aRef", rootNode)

        // Assert
        assertEquals(1, referenceChanged)
    }

    @Test
    fun changeDetectionWorksForAddedChild() {
        // Arrange
        var childrenChanged = 0
        val branch = loadModelsFromJsonAsBranch(arrayOf(emptyRoot))
        branch.addListener {
            when (it) {
                is ChildrenChanged -> childrenChanged++
                else -> {}
            }
        }
        val rootNode = branch.rootNode

        // Act
        rootNode.addNewChild("aRole", -1, GeneratedConcept("aConceptUid"))

        // Assert
        assertEquals(1, childrenChanged)
    }

    @Test
    fun changeDetectionWorksForMovedChild() {
        // Arrange
        var childrenChanged = 0
        var containmentChanged = 0
        val branch = loadModelsFromJsonAsBranch(arrayOf(emptyRoot))
        branch.addListener {
            when (it) {
                is ChildrenChanged -> childrenChanged++
                is ContainmentChanged -> containmentChanged++
                else -> {}
            }
        }
        val rootNode = branch.rootNode
        val childNode = rootNode.addNewChild("aRole", -1, GeneratedConcept("aConceptUid"))
        childrenChanged = 0

        // Act
        rootNode.moveChild("anotherRole", -1, childNode)

        // Assert
        assertEquals(2, childrenChanged)
        assertEquals(1, containmentChanged)
    }

    @Test
    fun changeDetectionWorksForRemovedChild() {
        // Arrange
        var childrenChanged = 0
        var containmentChanged = 0
        val branch = loadModelsFromJsonAsBranch(arrayOf(emptyRoot))
        branch.addListener {
            when (it) {
                is ChildrenChanged -> childrenChanged++
                is ContainmentChanged -> containmentChanged++
                else -> {}
            }
        }
        val rootNode = branch.rootNode
        val childNode = rootNode.addNewChild("aRole", -1, GeneratedConcept("aConceptUid"))
        childrenChanged = 0

        // Act
        rootNode.removeChild(childNode)

        // Assert
        assertEquals(1, childrenChanged)
    }
}
