import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * Bundles the WYSIWYG editor (../editor, TypeScript + ProseMirror) into `outputDir/editor`,
 * which becomes part of the app's assets. Needs Node.js on PATH; installs the editor's npm
 * dependencies first when they are missing or older than package-lock.json.
 */
abstract class BuildEditorTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val buildFiles: ConfigurableFileCollection

    @get:Internal
    abstract val editorDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun bundle() {
        val dir = editorDir.get().asFile
        val windows = System.getProperty("os.name").lowercase().contains("windows")
        // npm records what it installed in node_modules/.package-lock.json: a newer
        // package-lock.json (a dependency bumped) means node_modules is out of date.
        val installed = dir.resolve("node_modules/.package-lock.json")
        if (!installed.isFile || dir.resolve("package-lock.json").lastModified() > installed.lastModified()) {
            exec.exec {
                workingDir(dir)
                commandLine(if (windows) listOf("cmd", "/c", "npm", "ci") else listOf("npm", "ci"))
            }
        }
        val out = outputDir.get().asFile.resolve("editor")
        out.deleteRecursively()
        exec.exec {
            workingDir(dir)
            commandLine("node", "build.mjs", "--out", out.absolutePath)
        }
    }
}

val editorProject = rootProject.layout.projectDirectory.dir("editor")
val buildEditor = tasks.register<BuildEditorTask>("buildEditor") {
    sources.set(editorProject.dir("src"))
    buildFiles.from(
        editorProject.file("index.html"),
        editorProject.file("build.mjs"),
        editorProject.file("package.json"),
        editorProject.file("package-lock.json"),
    )
    editorDir.set(editorProject)
    outputDir.set(layout.buildDirectory.dir("generated/editorAssets"))
}

// Release signing: a dedicated key, kept outside the repository. keystore.properties (also
// outside version control) names it; see README.md. Without it, release builds are unsigned.
val keystoreProperties = rootProject.file("keystore.properties")

android {
    namespace = "app.pitchstone"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.pitchstone"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProperties.exists()) {
            val p = Properties().apply { keystoreProperties.inputStream().use { load(it) } }
            create("release") {
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric sets up file descriptors through JDK internals the JVM no longer exports.
        unitTests.all {
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*")
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(buildEditor, BuildEditorTask::outputDir)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.webkit)

    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.task.list.items)
    implementation(libs.commonmark.ext.autolink)
    implementation(libs.commonmark.ext.footnotes)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)
}
