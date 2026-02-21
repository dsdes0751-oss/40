import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services")
    id("org.jetbrains.kotlin.kapt")
}

fun Project.findReadelfCommand(): List<String> {
    val osName = System.getProperty("os.name")
    val isWindows = osName.contains("win", ignoreCase = true)
    val binaryName = if (isWindows) "llvm-readelf.exe" else "llvm-readelf"
    val hostTag = when {
        isWindows -> "windows-x86_64"
        osName.contains("mac", ignoreCase = true) -> "darwin-x86_64"
        else -> "linux-x86_64"
    }

    val candidates = mutableListOf<File>()

    fun addNdkRoot(root: File?) {
        if (root == null) return
        candidates += root.resolve("toolchains/llvm/prebuilt/$hostTag/bin/$binaryName")
        if (hostTag == "darwin-x86_64") {
            candidates += root.resolve("toolchains/llvm/prebuilt/darwin-arm64/bin/$binaryName")
        }
    }

    listOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "NDK_HOME", "NDK_ROOT").forEach { key ->
        System.getenv(key)?.takeIf { it.isNotBlank() }?.let { addNdkRoot(File(it)) }
    }

    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        val props = Properties()
        localPropertiesFile.inputStream().use(props::load)

        props.getProperty("ndk.dir")?.takeIf { it.isNotBlank() }?.let {
            addNdkRoot(File(it))
        }

        props.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }?.let { sdkPath ->
            val ndkBase = File(sdkPath).resolve("ndk")
            if (ndkBase.isDirectory) {
                ndkBase.listFiles()
                    ?.filter { it.isDirectory }
                    ?.sortedByDescending { it.name }
                    ?.forEach(::addNdkRoot)
            }
        }
    }

    candidates.firstOrNull { it.exists() }?.let { return listOf(it.absolutePath) }

    fun commandExists(command: String): Boolean {
        val path = System.getenv("PATH") ?: return false
        val pathSeparator = File.pathSeparatorChar
        val extensionCandidates = if (isWindows) listOf("", ".exe", ".cmd", ".bat") else listOf("")
        return path.split(pathSeparator).any { dir ->
            extensionCandidates.any { ext ->
                File(dir, command + ext).exists()
            }
        }
    }

    if (commandExists("llvm-readelf")) return listOf("llvm-readelf")
    if (commandExists("readelf")) return listOf("readelf")
    throw GradleException("Could not find llvm-readelf/readelf. Install NDK or set ANDROID_NDK_HOME.")
}

fun Project.registerNative16kCheckTask(taskName: String, dependsOnTask: String, artifactDir: String) {
    tasks.register(taskName) {
        group = "verification"
        description = "Checks PT_LOAD alignment for every .so in the packaged artifact."
        dependsOn(dependsOnTask)

        doLast {
            val artifactRoot = layout.buildDirectory.dir(artifactDir).get().asFile
            val artifactFile = artifactRoot.walkTopDown()
                .firstOrNull { it.isFile && (it.extension == "apk" || it.extension == "aab") }
                ?: throw GradleException("No APK/AAB found under ${artifactRoot.absolutePath}")

            val readelfCommand = findReadelfCommand()
            val extractDir = temporaryDir.resolve("native16kCheck").apply {
                deleteRecursively()
                mkdirs()
            }

            copy {
                from(zipTree(artifactFile))
                into(extractDir)
                include("**/*.so")
            }

            val soFiles = extractDir.walkTopDown()
                .filter { it.isFile && it.extension == "so" }
                .toList()
            if (soFiles.isEmpty()) {
                throw GradleException("No native .so files found in ${artifactFile.absolutePath}")
            }

            val loadRegex = Regex("""^\s*LOAD\s+.*\s(0x[0-9A-Fa-f]+|\d+)\s*$""")
            val violations = mutableListOf<String>()
            var checkedCount = 0

            soFiles.forEach { so ->
                val relativePath = so.relativeTo(extractDir).invariantSeparatorsPath
                val requires16k = relativePath.contains("/arm64-v8a/") || relativePath.contains("/x86_64/")
                if (!requires16k) return@forEach
                checkedCount += 1

                val process = ProcessBuilder(readelfCommand + listOf("-lW", so.absolutePath))
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val exitCode = process.waitFor()

                if (exitCode != 0) {
                    violations += "$relativePath: readelf failed"
                    return@forEach
                }

                val aligns = output
                    .lineSequence()
                    .mapNotNull { line ->
                        val match = loadRegex.find(line) ?: return@mapNotNull null
                        val token = match.groupValues[1]
                        token.removePrefix("0x").toLongOrNull(16) ?: token.toLongOrNull()
                    }
                    .toList()

                if (aligns.isEmpty()) {
                    violations += "$relativePath: no PT_LOAD headers"
                    return@forEach
                }

                val bad = aligns.filter { it < 0x4000L || it % 0x4000L != 0L }
                if (bad.isNotEmpty()) {
                    val hex = bad.joinToString(", ") { "0x${it.toString(16)}" }
                    violations += "$relativePath: PT_LOAD align=$hex"
                }
            }

            if (checkedCount == 0) {
                throw GradleException(
                    "No arm64-v8a/x86_64 native .so files found in ${artifactFile.absolutePath}"
                )
            }

            if (violations.isNotEmpty()) {
                throw GradleException(
                    "16KB page-size check failed for ${artifactFile.name}\n" +
                        violations.joinToString(separator = "\n", prefix = " - ")
                )
            }

            println("16KB page-size check passed: ${artifactFile.absolutePath}")
        }
    }
}

android {
    namespace = "com.tuna.proj_01"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tuna.proj_01"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation(platform("com.google.firebase:firebase-bom:34.8.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.android.gms:play-services-auth:21.5.0")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-functions")

    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")

    implementation("com.github.chrisbanes:PhotoView:2.3.0")
    implementation("com.github.bumptech.glide:glide:5.0.5")
    implementation("androidx.activity:activity-ktx:1.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    implementation("com.android.billingclient:billing-ktx:6.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
}

registerNative16kCheckTask(
    taskName = "verifyDebugNativeLib16kPageSize",
    dependsOnTask = "packageDebug",
    artifactDir = "outputs/apk/debug"
)

registerNative16kCheckTask(
    taskName = "verifyReleaseNativeLib16kPageSize",
    dependsOnTask = "bundleRelease",
    artifactDir = "outputs/bundle/release"
)

tasks.named("check").configure {
    dependsOn("verifyDebugNativeLib16kPageSize")
}
