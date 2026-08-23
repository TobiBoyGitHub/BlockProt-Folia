import org.kohsuke.github.GHReleaseBuilder
import org.kohsuke.github.GitHub

buildscript {
    repositories {
        maven("https://plugins.gradle.org/m2/")
    }

    dependencies {
        classpath("org.kohsuke:github-api:1.326")
    }
}

plugins {
    id("org.gradle.java-library")
    id("org.ajoberstar.grgit") version "5.3.3"
    id("com.diffplug.spotless") version "8.10.0"
}

fun gitBranchName(): String {
    val env = System.getenv()
    if (env["GITHUB_REF"] != null) {
        val branch = env["GITHUB_REF"]!!
        return branch.substring(branch.lastIndexOf("/") + 1)
    }

    val branch = grgit.branch.current().name
    return branch.substring(branch.lastIndexOf("/") + 1)
}

val env: MutableMap<String, String> = System.getenv()
val blockProtVersion: String = project.property("blockProtVersion") as String

allprojects {
    apply(plugin = "org.gradle.java-library")
    apply(plugin = "com.diffplug.spotless")

    group = "de.sean.blockprot"
    version = blockProtVersion

    repositories {
        mavenLocal()
        maven("https://jitpack.io") {
            name = "JitPack"
        }
        mavenCentral()
    }

    tasks.compileJava {
        options.release.set(25)
        java.sourceCompatibility = JavaVersion.VERSION_25
        java.targetCompatibility = JavaVersion.VERSION_25
    }

    ext["gitBranchName"] = gitBranchName()

    tasks.jar {
        // The default configuration for the archivesName is
        // [baseName]-[appendix]-[version]-[classifier].[extension]
        archiveClassifier.set(
            if (ext["gitBranchName"] == "master" || ext["gitBranchName"] == "HEAD") null
            else (ext["gitBranchName"] as String)
        )
    }

    // We use Spotless (with the licenseHeader step) instead of the old
    // org.cadixdev.licenser plugin, which was last released in 2021 and is
    // incompatible with Gradle 9+. We also previously considered spotless'
    // Java formatter itself, but it had too many issues — so we only use
    // Spotless here for the license header check/apply, not formatting.
    afterEvaluate {
        configure<com.diffplug.gradle.spotless.SpotlessExtension> {
            java {
                target("src/**/*.java")
                licenseHeaderFile(rootProject.file("HEADER.txt"))
                    .updateYearWithLatest(false)
            }
        }
    }
}

tasks.register("github") {
    onlyIf {
        env["GITHUB_TOKEN"] != null
    }

    doLast {
        val github = GitHub.connectUsingOAuth(env["GITHUB_TOKEN"] as String)
        val repository = github.getRepository(env["GITHUB_REPOSITORY"])

        val releaseBuilder = GHReleaseBuilder(repository, version as String)
        releaseBuilder.name(version as String)
        releaseBuilder.body(env["CHANGELOG"] ?: "No changelog.")
        releaseBuilder.commitish(gitBranchName())

        // Get the output JARs for each subproject.
        val files = mutableListOf<File?>()
        subprojects.filter { it.name != "common" }.forEach {
            val dir = it.layout.buildDirectory.dir("libs").get().asFile.path + "/"
            files.add(file(dir).listFiles()?.last { file ->
                file.nameWithoutExtension.endsWith("all")
            })
        }

        val ghRelease = releaseBuilder.create()
        files.forEach {
            ghRelease.uploadAsset(it, "application/java-archive")
        }
        // We set the proper name here, as "releaseBuilder.name" is also used for the tag name.
        ghRelease.update().name("BlockProt $version").update()
    }
}
