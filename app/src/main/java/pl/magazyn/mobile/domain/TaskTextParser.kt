package pl.magazyn.mobile.domain

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class ParseConfidence { CERTAIN, LIKELY, REVIEW }

data class TaskPlaceLookup(val id: String, val name: String, val aliases: List<String>)
data class TaskEmployeeLookup(val id: String, val firstName: String, val lastName: String)

data class ParsedTaskPerson(
    val employeeId: String?,
    val displayText: String,
    val note: String = "",
    val confidence: ParseConfidence,
    val recordId: String? = null,
    val isCompleted: Boolean = false,
    val completedAtEpochMillis: Long? = null,
    val completedBy: String? = null,
)

data class ParsedTaskStep(
    val time: String? = null,
    val placeId: String? = null,
    val placeText: String = "",
    val note: String = "",
    val people: List<ParsedTaskPerson> = emptyList(),
    val confidence: ParseConfidence = ParseConfidence.REVIEW,
    val recordId: String? = null,
    val isCompleted: Boolean = false,
    val completedAtEpochMillis: Long? = null,
    val completedBy: String? = null,
)

data class ParsedTaskDraft(
    val title: String,
    val date: String?,
    val description: String,
    val steps: List<ParsedTaskStep>,
    val confidence: ParseConfidence,
)

fun placeAliasConflicts(alias: String, targetPlaceId: String, labelsByPlace: Map<String, List<String>>): Boolean {
    val key = ImportParser.key(alias)
    return key.isNotBlank() && labelsByPlace.any { (placeId, labels) ->
        placeId != targetPlaceId && labels.any { ImportParser.key(it) == key }
    }
}

/** Zachowawczy parser lokalny list przejazdów i innych zadań wieloetapowych. */
class TaskTextParser {
    fun looksLikeTask(text: String): Boolean {
        val key = ImportParser.key(text)
        return TASK_TITLES.any { key.contains(it) } || text.lineSequence().any { STEP_LINE.matches(it.trim()) }
    }

    fun parse(
        text: String,
        places: List<TaskPlaceLookup>,
        employees: List<TaskEmployeeLookup>,
        today: LocalDate = LocalDate.now(),
    ): ParsedTaskDraft {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        if (lines.isEmpty()) return ParsedTaskDraft("Zadanie", null, "", emptyList(), ParseConfidence.REVIEW)

        val date = when {
            ImportParser.key(text).split(' ').contains("jutro") -> today.plusDays(1).toString()
            ImportParser.key(text).split(' ').contains("dzis") || ImportParser.key(text).split(' ').contains("dzisiaj") -> today.toString()
            else -> null
        }
        val title = TASK_TITLES.firstOrNull { title -> ImportParser.key(text).contains(title) }
            ?.replaceFirstChar { it.uppercase() } ?: "Zadanie"
        val steps = mutableListOf<ParsedTaskStep>()
        val description = mutableListOf<String>()
        var currentStepIndex: Int? = null
        var mainTime: String? = null

        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trim().trimStart('-', '•').trim()
            val headerWithoutTitle = if (index == 0) removeTaskHeaderWords(line) else line
            val stepMatch = STEP_LINE.matchEntire(headerWithoutTitle)
            if (stepMatch != null && !containsAirlineBeforeTime(headerWithoutTitle, stepMatch.range.first)) {
                val time = normalizeTime(stepMatch.groupValues[1])
                val placeText = stepMatch.groupValues[2].trim().trim('.', ',', ';')
                if (placeText.isBlank()) {
                    mainTime = time
                    currentStepIndex = null
                } else {
                    val place = resolvePlace(placeText, places)
                    steps += ParsedTaskStep(
                        time = time,
                        placeId = place?.id,
                        placeText = place?.name ?: placeText,
                        confidence = if (place != null) ParseConfidence.CERTAIN else ParseConfidence.REVIEW,
                    )
                    currentStepIndex = steps.lastIndex
                }
                return@forEachIndexed
            }
            if (index == 0 && headerWithoutTitle.isBlank()) return@forEachIndexed

            val person = parsePersonLine(line, employees, places)
            if (person != null) {
                val (parsedPerson, place) = person
                val current = currentStepIndex?.let(steps::get)
                val belongsToCurrent = current != null && (place == null || current.placeId == place.id || ImportParser.key(current.placeText) == ImportParser.key(place.name))
                if (belongsToCurrent) {
                    steps[currentStepIndex!!] = current!!.copy(people = current.people + parsedPerson)
                } else {
                    val unresolvedPlace = extractUnresolvedPlace(line, parsedPerson.displayText, parsedPerson.note, places)
                    steps += ParsedTaskStep(
                        time = mainTime,
                        placeId = place?.id,
                        placeText = place?.name ?: unresolvedPlace,
                        people = listOf(parsedPerson),
                        confidence = when {
                            place != null && parsedPerson.employeeId != null -> ParseConfidence.CERTAIN
                            place != null || parsedPerson.employeeId != null -> ParseConfidence.LIKELY
                            else -> ParseConfidence.REVIEW
                        },
                    )
                    currentStepIndex = if (mainTime == null) steps.lastIndex else null
                }
                return@forEachIndexed
            }

            if (index == 0 && headerWithoutTitle.isNotBlank()) {
                val headerTime = TIME.find(headerWithoutTitle)?.value
                if (headerTime != null) mainTime = normalizeTime(headerTime)
                val remainder = TIME.replace(headerWithoutTitle, "").trim().trim('.', ',', ';')
                if (remainder.isNotBlank()) description += remainder
            } else {
                description += line
            }
        }

        return ParsedTaskDraft(
            title = title,
            date = date,
            description = description.joinToString("\n"),
            steps = steps,
            confidence = if (steps.isNotEmpty() && steps.all { it.confidence == ParseConfidence.CERTAIN }) ParseConfidence.CERTAIN else ParseConfidence.LIKELY,
        )
    }

    private fun parsePersonLine(
        line: String,
        employees: List<TaskEmployeeLookup>,
        places: List<TaskPlaceLookup>,
    ): Pair<ParsedTaskPerson, TaskPlaceLookup?>? {
        val clean = line.trim().trimEnd('.')
        val key = ImportParser.key(clean)
        if (key.isBlank() || AIRLINES.any { key == it }) return null
        val matched = employees.mapNotNull { employee ->
            val normal = ImportParser.key("${employee.firstName} ${employee.lastName}")
            val reversed = ImportParser.key("${employee.lastName} ${employee.firstName}")
            listOf(normal, reversed).filter(String::isNotBlank).firstOrNull { key.startsWith(it) }?.let { employee to it }
        }.maxByOrNull { it.second.length }

        val displayText: String
        val employeeId: String?
        val consumedWords: Int
        if (matched != null) {
            displayText = "${matched.first.lastName} ${matched.first.firstName}".trim()
            employeeId = matched.first.id
            consumedWords = 2
        } else {
            val words = clean.split(Regex("\\s+")).filter(String::isNotBlank)
            val nameToken = Regex("^[\\p{Lu}ŻŹĆĄŚĘŁÓŃ][\\p{L}'-]+[.,]?$")
            if (words.size < 2 || words.take(2).any { token -> token.any(Char::isDigit) || !nameToken.matches(token) } || words.first().endsWith('.') || ImportParser.key(words.first()) in NON_PERSON_STARTS) return null
            displayText = words.take(2).joinToString(" ").trim('.', ',')
            employeeId = null
            consumedWords = 2
        }
        val remainder = clean.split(Regex("\\s+")).drop(consumedWords).joinToString(" ")
        val place = resolvePlaceInside(remainder, places)
        val parentheses = PAREN.findAll(remainder).joinToString("; ") { it.groupValues[1].trim() }
        val withoutPlace = place?.let { removePlaceLabel(remainder, it) } ?: remainder
        val note = PAREN.replace(withoutPlace, " ").trim().trim('.', ',', ';')
            .let { listOf(it, parentheses).filter(String::isNotBlank).distinct().joinToString("; ") }
        return ParsedTaskPerson(
            employeeId = employeeId,
            displayText = displayText,
            note = note,
            confidence = if (employeeId != null) ParseConfidence.CERTAIN else ParseConfidence.REVIEW,
        ) to place
    }

    private fun resolvePlace(value: String, places: List<TaskPlaceLookup>): TaskPlaceLookup? {
        val key = ImportParser.key(value)
        return places.firstOrNull { place ->
            (listOf(place.name) + place.aliases).any { ImportParser.key(it) == key }
        }
    }

    private fun resolvePlaceInside(value: String, places: List<TaskPlaceLookup>): TaskPlaceLookup? {
        val key = ImportParser.key(value)
        return places.flatMap { place -> (listOf(place.name) + place.aliases).map { place to ImportParser.key(it) } }
            .filter { (_, label) -> label.isNotBlank() && (key == label || key.startsWith("$label ") || key.endsWith(" $label") || key.contains(" $label ")) }
            .maxByOrNull { it.second.length }?.first
    }

    private fun removePlaceLabel(value: String, place: TaskPlaceLookup): String {
        val labels = (listOf(place.name) + place.aliases).sortedByDescending(String::length)
        val label = labels.firstOrNull { value.contains(it, ignoreCase = true) } ?: return value
        return value.replace(label, "", ignoreCase = true)
    }

    private fun extractUnresolvedPlace(line: String, person: String, note: String, places: List<TaskPlaceLookup>): String {
        var rest = line
        person.split(' ').forEach { rest = rest.replace(it, "", ignoreCase = true) }
        if (note.isNotBlank()) rest = rest.replace(note, "", ignoreCase = true)
        PAREN.findAll(rest).forEach { rest = rest.replace(it.value, "") }
        val known = resolvePlaceInside(rest, places)
        if (known != null) return known.name
        return rest.trim().trim('.', ',', ';')
    }

    private fun removeTaskHeaderWords(value: String): String {
        var result = value
        TASK_TITLES.forEach { result = result.replace(Regex("(?i)\\b$it\\b"), " ") }
        result = result.replace(Regex("(?i)\\b(dziś|dzis|dzisiaj|jutro)\\b"), " ")
        return result.replace(Regex("\\s+"), " ").trim().trim('.', ',', ';')
    }

    private fun containsAirlineBeforeTime(line: String, timeStart: Int): Boolean {
        val before = ImportParser.key(line.take(timeStart))
        return AIRLINES.any { before.contains(it) }
    }

    private fun normalizeTime(value: String): String = runCatching {
        LocalTime.parse(value.padStart(5, '0'), DateTimeFormatter.ofPattern("H:mm")).format(DateTimeFormatter.ofPattern("HH:mm"))
    }.getOrDefault(value)

    private companion object {
        val TASK_TITLES = listOf("przyjazd", "zjazd", "skatte", "zadanie")
        val AIRLINES = listOf("wizzair", "sas", "norwegian")
        val NON_PERSON_STARTS = setOf("zjazd", "przyjazd", "skatte", "wizzair", "sas", "norwegian", "takze", "teraz", "ostatnio", "patrze")
        val TIME = Regex("(?<!\\d)([0-2]?\\d:[0-5]\\d)(?!\\d)")
        val STEP_LINE = Regex("^([0-2]?\\d:[0-5]\\d)(?:\\s+(.+))?$")
        val PAREN = Regex("\\(([^)]*)\\)")
    }
}
