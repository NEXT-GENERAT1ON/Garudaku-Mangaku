import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Akirune"
    versionCode = 5
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"
    // NOTE: removed `theme = "mangathemesia"` — Akirune runs on Blogger with the
    // "ZeistManga" template, not WordPress, so the MangaThemesia theme class
    // does not apply here. This source has its own standalone implementation
    // instead of inheriting a shared multisrc theme.

    source {
        lang = "id"
        baseUrl = "https://www.akirune.my.id"
    }
}
