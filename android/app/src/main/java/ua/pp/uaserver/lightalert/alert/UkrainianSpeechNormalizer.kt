package ua.pp.uaserver.lightalert.alert

object UkrainianSpeechNormalizer {

    private val locativeHours = arrayOf(
        "нульовій", "першій", "другій", "третій", "четвертій", "п’ятій",
        "шостій", "сьомій", "восьмій", "дев’ятій", "десятій", "одинадцятій",
        "дванадцятій", "тринадцятій", "чотирнадцятій", "п’ятнадцятій", "шістнадцятій",
        "сімнадцятій", "вісімнадцятій", "дев’ятнадцятій", "двадцятій",
        "двадцять першій", "двадцять другій", "двадцять третій"
    )

    private val nominativeHours = arrayOf(
        "нульова", "перша", "друга", "третя", "четверта", "п’ята",
        "шоста", "сьома", "восьма", "дев’ята", "десята", "одинадцята",
        "дванадцята", "тринадцята", "чотирнадцята", "п’ятнадцята", "шістнадцята",
        "сімнадцята", "вісімнадцята", "дев’ятнадцята", "двадцята",
        "двадцять перша", "двадцять друга", "двадцять третя"
    )

    private val onesFem = arrayOf(
        "", "одна", "дві", "три", "чотири", "п’ять", "шість", "сім", "вісім", "дев’ять"
    )

    private val teens = arrayOf(
        "десять", "одинадцять", "дванадцять", "тринадцять", "чотирнадцять",
        "п’ятнадцять", "шістнадцять", "сімнадцять", "вісімнадцять", "дев’ятнадцять"
    )

    private val tens = arrayOf("", "", "двадцять", "тридцять", "сорок", "п’ятдесят")

    fun pluralWord(n: Int, one: String, few: String, many: String): String {
        val mod100 = n % 100
        val mod10 = n % 10
        return when {
            mod100 in 11..14 -> many
            mod10 == 1 -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }

    fun formatMinutes(mm: Int): String {
        if (mm <= 0) return ""
        val words = when {
            mm < 10 -> onesFem.getOrElse(mm) { mm.toString() }
            mm < 20 -> teens.getOrElse(mm - 10) { mm.toString() }
            else -> {
                val t = mm / 10
                val u = mm % 10
                tens.getOrElse(t) { "" } + if (u > 0) " ${onesFem.getOrElse(u) { "" }}" else ""
            }
        }
        return "$words ${pluralWord(mm, "хвилина", "хвилини", "хвилин")}".trim()
    }

    fun formatTime(hh: Int, mm: Int, withPreposition: Boolean): String {
        if (hh == 0 && mm == 0) return "опівночі"
        return if (withPreposition) {
            val prep = if (hh == 11) "об" else "о"
            val hLoc = locativeHours.getOrElse(hh) { hh.toString() }
            val minPart = if (mm > 0) " ${formatMinutes(mm)}" else ""
            "$prep $hLoc годині$minPart"
        } else {
            val hNom = nominativeHours.getOrElse(hh) { hh.toString() }
            val minPart = if (mm > 0) " ${formatMinutes(mm)}" else ""
            "$hNom година$minPart"
        }
    }

    fun normalize(rawText: String, eventTime: String, groupLabel: String): String {
        var text = rawText.trim()
        val cleanGroup = groupLabel.replace(Regex("^GPV", RegexOption.IGNORE_CASE), "").trim()
        val hadTimeRef = Regex("\\{time\\}", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
            Regex("\\b(?:[01]?\\d|2[0-3]):[0-5]\\d\\b").containsMatchIn(text)

        if (eventTime.isNotBlank()) {
            text = text.replace(Regex("\\{time\\}", RegexOption.IGNORE_CASE), eventTime)
        }
        text = text.replace(Regex("\\{group\\}", RegexOption.IGNORE_CASE), cleanGroup)

        if (eventTime.isNotBlank() && !hadTimeRef) {
            val parts = eventTime.split(":")
            val eh = parts.getOrNull(0)?.toIntOrNull()
            val em = parts.getOrNull(1)?.toIntOrNull()
            if (eh != null && em != null) {
                text = text.trimEnd('.', ' ') + ", орієнтовно ${formatTime(eh, em, true)}."
            }
        }

        val prepTimeRegex = Regex(
            "(^|[\\s,(])(о|об)\\s+([01]?\\d|2[0-3]):([0-5]\\d)\\b",
            RegexOption.IGNORE_CASE
        )
        text = prepTimeRegex.replace(text) { m ->
            val prefix = m.groupValues[1]
            val prepWord = m.groupValues[2]
            val hh = m.groupValues[3].toIntOrNull() ?: 0
            val mm = m.groupValues[4].toIntOrNull() ?: 0
            var formatted = formatTime(hh, mm, true)
            if (prepWord.isNotEmpty() && prepWord[0].isUpperCase()) {
                formatted = formatted.replaceFirstChar { it.uppercaseChar() }
            }
            prefix + formatted
        }

        val plainTimeRegex = Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b")
        text = plainTimeRegex.replace(text) { m ->
            val hh = m.groupValues[1].toIntOrNull() ?: 0
            val mm = m.groupValues[2].toIntOrNull() ?: 0
            formatTime(hh, mm, false)
        }

        text = Regex("(\\d+)\\s*хв\\.?(?=\\s|$|[.,!?;:])", RegexOption.IGNORE_CASE).replace(text) { m ->
            val n = m.groupValues[1].toIntOrNull() ?: 0
            "${m.groupValues[1]} ${pluralWord(n, "хвилина", "хвилини", "хвилин")}"
        }
        text = Regex("(\\d+)\\s*год\\.?(?=\\s|$|[.,!?;:])", RegexOption.IGNORE_CASE).replace(text) { m ->
            val n = m.groupValues[1].toIntOrNull() ?: 0
            "${m.groupValues[1]} ${pluralWord(n, "година", "години", "годин")}"
        }
        text = Regex("(\\d+)\\s*сек\\.?(?=\\s|$|[.,!?;:])", RegexOption.IGNORE_CASE).replace(text) { m ->
            val n = m.groupValues[1].toIntOrNull() ?: 0
            "${m.groupValues[1]} ${pluralWord(n, "секунда", "секунди", "секунд")}"
        }
        text = Regex("(^|\\s)е/е(?=\\s|$|[.,!?;:])", RegexOption.IGNORE_CASE).replace(text, "$1електроенергії")
        text = Regex("(^|\\s)е\\.е\\.(?=\\s|$|[.,!?;:])", RegexOption.IGNORE_CASE).replace(text, "$1електроенергії")
        text = Regex("(\\d+)\\s*кВт", RegexOption.IGNORE_CASE).replace(text, "$1 кіловат")
        text = Regex("(\\d+)\\s*%").replace(text, "$1 відсотків")
        text = text.replace(Regex("\\s*[—–]\\s+"), ", ")
        text = text.replace(Regex("\\s{2,}"), " ").trim()
        return text
    }

    fun splitSentences(text: String): List<String> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        return clean.replace(Regex("([.!?…]+)\\s+"), "$1\n")
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}
