package com.lingshu.feature.control.domain

interface ICommandParser {
    fun parse(userInput: String): Command
}
