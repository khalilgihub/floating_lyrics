package com.example.floating_lyrics

object FontUtils {

    private val fonts = listOf(
        "Roboto",
        "sans-serif",
        "monospace",
        "serif"
    )

    fun getNextFont(currentFont: String?): String {
        val currentIndex = fonts.indexOf(currentFont)
        val nextIndex = (currentIndex + 1) % fonts.size
        return fonts[nextIndex]
    }
}
