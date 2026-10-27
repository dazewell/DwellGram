plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        register("telegramBuildPlugin") {
            id = "org.telegram.build-plugin"
            implementationClass = "org.telegram.plugin.TelegramBuildPlugin"
        }
        register("telegramBuildAppPlugin") {
            id = "org.telegram.build-app-plugin"
            implementationClass = "org.telegram.plugin.TelegramBuildAppPlugin"
        }
    }
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(gradleApi())
    implementation("com.android.tools.build:gradle:9.3.1")
    // NagramX: provides com.android.built-in-kotlin; keep in step with the AGP version above.
    implementation("com.android.tools.build:gradle-kotlin:9.3.1")
    implementation("com.google.code.gson:gson:2.14.0")
}
