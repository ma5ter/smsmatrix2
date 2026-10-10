pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()

	}
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
	repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
	@Suppress("UnstableApiUsage")
	repositories {
		google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "SMS-Matrix"
include(":app")
 