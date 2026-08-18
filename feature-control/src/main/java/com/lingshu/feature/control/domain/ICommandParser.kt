package com.lingshu.feature.control.domain

interface ICommandParser {
    fun parse(userInput: String): Command

    /** Day3-1：当返回 Unknown 时，给用户展示 TopN 相似示例（按模糊匹配/关键词相似度排序） */
    fun topSimilarExamples(userInput: String, limit: Int = 5): List<String>
}
