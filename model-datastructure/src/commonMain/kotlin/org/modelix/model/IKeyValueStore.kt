/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.modelix.model

import org.modelix.model.lazy.BulkQuery
import org.modelix.model.lazy.IBulkQuery
import org.modelix.model.lazy.IDeserializingKeyValueStore

interface IKeyValueStore {
    fun newBulkQuery(deserializingCache: IDeserializingKeyValueStore): IBulkQuery = BulkQuery(deserializingCache)
    operator fun get(key: String): String?
    fun put(key: String, value: String?)
    fun getAll(keys: Iterable<String>): Map<String, String?>
    fun putAll(entries: Map<String, String?>)
    fun prefetch(key: String)
    fun listen(key: String, listener: IKeyListener)
    fun removeListener(key: String, listener: IKeyListener)
    fun getPendingSize(): Int
}
