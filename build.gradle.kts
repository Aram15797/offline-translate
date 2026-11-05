plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

tasks.register<Exec>("checkSourcePolicy") {
    commandLine("python3", layout.projectDirectory.file("scripts/check_source_policy.py").asFile.absolutePath)
}
