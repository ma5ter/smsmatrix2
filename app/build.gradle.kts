import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	id("com.android.application")
	id("org.jetbrains.kotlin.plugin.compose")
}

android {
	namespace = "su.mya.sms_matrix"
	compileSdk = 37

	defaultConfig {
		applicationId = "su.mya.sms_matrix"
		minSdk = 29
		targetSdk = 37
		versionCode = 20
		versionName = "0.2.0"
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
		ndk {
			abiFilters.clear()
			abiFilters.addAll(listOf("arm64-v8a"))
		}
	}

	dependenciesInfo {
		includeInApk = false
		includeInBundle = false
	}

	signingConfigs {
		create("release") {
			val keystoreFile = file("release.keystore")
			if (keystoreFile.exists()) {
				storeFile = keystoreFile
				storePassword = System.getenv("KEYSTORE_PASSWORD")
				keyAlias = System.getenv("KEY_ALIAS")
				keyPassword = System.getenv("KEY_PASSWORD")
			}
		}
	}

	buildTypes {
		release {
			isMinifyEnabled = false
			signingConfig = signingConfigs.getByName("release")
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro"
			)
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}

	buildFeatures {
		compose = true
	}

	packaging {
		jniLibs {
			keepDebugSymbols += listOf(
				"**/libmatrix_sdk_crypto_ffi.so",
				"**/libjnidispatch.so"
			)
		}
	}
}

kotlin {
	compilerOptions {
		jvmTarget.set(JvmTarget.JVM_17)
	}
}

dependencies {
	val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
	implementation(composeBom)
	androidTestImplementation(composeBom)

	implementation(libs.androidx.activity.compose)
	implementation(libs.androidx.appcompat)
	implementation(libs.androidx.material3)
	implementation(libs.androidx.preference)
	implementation(libs.androidx.startup.runtime)
	implementation(libs.androidx.ui)
	implementation(libs.androidx.ui.graphics)
	implementation(libs.androidx.ui.tooling.preview)

	// Matrix SDK management
	implementation(libs.matrix.android.sdk2)

	debugImplementation(libs.androidx.ui.tooling)
	debugImplementation(libs.androidx.ui.test.manifest)
}