package pl.magazyn.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.magazyn.mobile.MagazynApplication
import pl.magazyn.mobile.data.ParserLearningRuleEntity
import pl.magazyn.mobile.domain.ImportParser

class LearningRulesViewModel(application: Application) : AndroidViewModel(application) {
    private val database = (application as MagazynApplication).database
    val rules = database.learningRuleDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(existing: ParserLearningRuleEntity?, type: String, source: String, target: String, variant: String, unit: String) {
        val key = ImportParser.key(source)
        if (key.isBlank() || target.isBlank() || unit.isBlank()) return
        viewModelScope.launch {
            database.learningRuleDao().upsert(
                ParserLearningRuleEntity(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    triggerKey = key,
                    sourceLabel = source.trim(),
                    learnedName = target.trim(),
                    learnedVariant = variant.trim().ifBlank { null },
                    learnedUnit = unit.trim(),
                    confirmations = existing?.confirmations ?: 1,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                    ruleType = type,
                    resultExtra = variant.trim(),
                    isEnabled = true,
                ),
            )
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { database.learningRuleDao().delete(id) }
    }
}
