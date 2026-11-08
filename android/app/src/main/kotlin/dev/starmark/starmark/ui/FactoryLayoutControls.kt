package dev.starmark.starmark.ui

internal data class FactoryLayoutOption(val group: Int, val name: String)
internal data class FactoryAxisChoice(val value: String, val destination: FactoryLayoutOption)
internal data class FactoryLayoutAxis(val name: String, val selected: String, val choices: List<FactoryAxisChoice>)

/** The OEM picker changes one declared orientation axis, never synthesizing a layout. */
internal fun factoryLayoutAxes(options: List<FactoryLayoutOption>, selected: FactoryLayoutOption?): List<FactoryLayoutAxis> {
    if (options.size < 2) return emptyList()
    val current = selected ?: options.first()
    val vertical = listOf("top", "center", "bottom")
    val horizontal = listOf("left", "middle", "right")
    val combined = options.all { it.name.substringBefore('_') in vertical && it.name.substringAfter('_') in horizontal }
    if (combined) return listOf("vertical", "horizontal").mapNotNull { axis ->
        val isVertical = axis == "vertical"
        fun value(option: FactoryLayoutOption) = if (isVertical) option.name.substringBefore('_') else option.name.substringAfter('_')
        fun other(option: FactoryLayoutOption) = if (isVertical) option.name.substringAfter('_') else option.name.substringBefore('_')
        val choices = (if (isVertical) vertical else horizontal).mapNotNull { v ->
            val candidates = options.filter { value(it) == v }
            (candidates.firstOrNull { other(it) == other(current) } ?: candidates.firstOrNull())?.let { FactoryAxisChoice(v, it) }
        }
        if (choices.size < 2) null else FactoryLayoutAxis(axis, value(current), choices)
    }
    val axis = when {
        options.all { it.name in vertical } -> "vertical"
        options.all { it.name in horizontal } -> "horizontal"
        options.all { it.name.contains("_border_") } -> "border"
        else -> "layout"
    }
    val sorted = when (axis) {
        "vertical" -> options.sortedBy { vertical.indexOf(it.name) }
        "horizontal" -> options.sortedBy { horizontal.indexOf(it.name) }
        else -> options
    }
    return listOf(FactoryLayoutAxis(axis, current.name, sorted.map { FactoryAxisChoice(it.name, it) }))
}
