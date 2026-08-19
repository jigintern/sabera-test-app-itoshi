pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 認証情報は名前から決まる GitHubPackagesUsername / GitHubPackagesPassword を
        // Gradle が探す。~/.gradle/gradle.properties に置く。
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/jig-SABERA/sabera-sdk-packages")
            credentials(PasswordCredentials::class)
        }
    }
}

rootProject.name = "sabera-test-app-itoshi"
include(":app")
