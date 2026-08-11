package com.lingshu.feature.rag.domain

import com.lingshu.core.common.error.Result
import java.io.File

interface IRagService {
    suspend fun uploadDocument(file: File): Result<Unit>
    fun listDocuments(): List<Document>
    suspend fun deleteDocument(documentId: String): Result<Unit>
    suspend fun search(query: String): Result<List<Chunk>>
    suspend fun ask(query: String): Result<String>
}
