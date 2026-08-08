package com.lingshu.agent.feature.knowledge

data class IndexingProgress

data class QaEntry

data class QaReference

sealed class KnowledgeEvent

object IndexingBusy

data class FileTypeNotSupported

data class DocumentEmpty

data class DocumentAdded

data class DocumentDeleted

object ClearedAll

data class ErrorOccurred

data class RagAnswerFailed

data class RagNoContextFallback

