package com.lingshu.agent.core.database

import androidx.room.ProvidedTypeConverter
import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lingshu.agent.core.database.entity.HealthDataType
import com.lingshu.agent.core.database.entity.HealthDataSource
import com.lingshu.agent.core.database.entity.MessageEntity
import com.lingshu.agent.core.database.entity.ModCategoryEntity
import com.lingshu.agent.core.model.BigFiveTraits
import com.lingshu.agent.core.model.ConversationStatus
import com.lingshu.agent.core.model.MessageRole
import com.lingshu.agent.core.model.PersonaRules
import com.lingshu.agent.core.model.SleepSegment
import javax.inject.Inject

@ProvidedTypeConverter
class Converters @Inject constructor() {

    private val gson = Gson()

    @TypeConverter
    fun fromMessageRole(value: MessageRole): String = value.name

    @TypeConverter
    fun toMessageRole(value: String): MessageRole = MessageRole.valueOf(value)

    @TypeConverter
    fun fromConversationStatus(value: ConversationStatus): String = value.name

    @TypeConverter
    fun toConversationStatus(value: String): ConversationStatus = ConversationStatus.valueOf(value)

    @TypeConverter
    fun fromMessageFeedback(value: MessageEntity.Feedback?): String? = value?.name

    @TypeConverter
    fun toMessageFeedback(value: String?): MessageEntity.Feedback? = value?.let { MessageEntity.Feedback.valueOf(it) }

    @TypeConverter
    fun fromModCategoryEntity(value: ModCategoryEntity): String = value.name

    @TypeConverter
    fun toModCategoryEntity(value: String): ModCategoryEntity = ModCategoryEntity.valueOf(value)

    @TypeConverter
    fun fromHealthDataType(value: HealthDataType): String = value.name

    @TypeConverter
    fun toHealthDataType(value: String): HealthDataType = HealthDataType.valueOf(value)

    @TypeConverter
    fun fromHealthDataSource(value: HealthDataSource): String = value.name

    @TypeConverter
    fun toHealthDataSource(value: String): HealthDataSource = HealthDataSource.valueOf(value)

    @TypeConverter
    fun fromStringList(value: List<String>): String = gson.toJson(value)

    @TypeConverter
    fun toStringList(value: String): List<String> {
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(value, type) ?: emptyList()
    }

    @TypeConverter
    fun fromStringMap(value: Map<String, String>): String = gson.toJson(value)

    @TypeConverter
    fun toStringMap(value: String): Map<String, String> {
        val type = object : TypeToken<Map<String, String>>() {}.type
        return gson.fromJson(value, type) ?: emptyMap()
    }

    @TypeConverter
    fun fromSleepSegmentList(value: List<SleepSegment>): String = gson.toJson(value)

    @TypeConverter
    fun toSleepSegmentList(value: String?): List<SleepSegment> {
        if (value == null) return emptyList()
        val type = object : TypeToken<List<SleepSegment>>() {}.type
        return gson.fromJson(value, type) ?: emptyList()
    }

    // ==================== BigFiveTraits 转换器 ====================

    @TypeConverter
    fun fromBigFiveTraits(traits: BigFiveTraits): String = gson.toJson(traits)

    @TypeConverter
    fun toBigFiveTraits(value: String): BigFiveTraits =
        gson.fromJson(value, BigFiveTraits::class.java) ?: BigFiveTraits.neutral()

    // ==================== PersonaRules 转换器 ====================

    @TypeConverter
    fun fromPersonaRules(rules: PersonaRules): String = gson.toJson(rules)

    @TypeConverter
    fun toPersonaRules(value: String): PersonaRules =
        gson.fromJson(value, PersonaRules::class.java) ?: PersonaRules()

    // ==================== Pair<String, String> 列表转换器 ====================

    @TypeConverter
    fun fromPairStringList(list: List<Pair<String, String>>): String = gson.toJson(list)

    @TypeConverter
    fun toPairStringList(value: String): List<Pair<String, String>> {
        val type = object : TypeToken<List<Pair<String, String>>>() {}.type
        return gson.fromJson(value, type) ?: emptyList()
    }
}
