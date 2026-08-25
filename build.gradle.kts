buildscript {
	repositories {
		google()
		mavenCentral()
	}
	dependencies {
		classpath(libs.gradle)
		classpath(libs.kotlin.gradle.plugin)
		classpath(libs.compose.compiler.gradle.plugin)
	}
}

tasks.register<Delete>("clean") {
	delete(rootProject.layout.buildDirectory)
}