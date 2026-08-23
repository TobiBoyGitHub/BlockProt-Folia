buildscript {
    repositories {
        maven("https://plugins.gradle.org/m2/")
    }
}

plugins {
    id("maven-publish")
    // The io.github.goooler.shadow fork is deprecated; development moved back
    // to GradleUp/shadow under the com.gradleup.shadow ID. We pin to 8.3.11
    // (the last 8.x-line release, backported for Gradle 9 support) rather than
    // jumping to the 9.x rewrite, since 9.x has a long list of breaking DSL
    // changes (duplicatesStrategy default, isEnableRelocation renamed, etc.)
    // that would need a separate, deliberate migration pass — not bundled
    // into this Folia migration. See https://gradleup.com/shadow/changes/
    id("com.gradleup.shadow") version "8.3.11"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

val nbtApiVersion: String = project.property("nbtApiVersion") as String
val townyVersion: String = project.property("townyVersion") as String
val papiVersion: String = project.property("papiVersion") as String
val worldGuardVersion: String = project.property("worldGuardVersion") as String

repositories {
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "PaperMC"
    }
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://maven.enginehub.org/repo/")
    maven("https://repo.codemc.org/repository/maven-public/") {
        name = "CodeMC"
        content {
            includeGroup("de.tr7zw")
        }
    }
}

dependencies {
    implementation(project(":common"))

    // Folia (extends Paper API, gives us RegionScheduler / EntityScheduler / AsyncScheduler)
    // NOTE: as of 2026 Folia moved to a new versioning scheme (MC_VERSION.build.N-stable).
    // 26.1.2.build.8-stable is the latest stable Folia build as of writing — verify against
    // https://papermc.io/downloads/folia before every release, since old build numbers get
    // superseded and this is a manually pinned version, not a floating range.
    compileOnly("dev.folia:folia-api:26.1.2.build.8-stable")
    compileOnly("org.apache.commons:commons-lang3:3.13.0")

    // bStats
    api("org.bstats:bstats-bukkit:3.0.2")

    // Dependencies
    implementation("de.tr7zw:item-nbt-api:$nbtApiVersion")
    implementation("org.enginehub:squirrelid:0.3.2")

    // Integrations
    implementation("com.github.TownyAdvanced:Towny:$townyVersion")
    implementation("me.clip:placeholderapi:$papiVersion")
    implementation("com.sk89q.worldguard:worldguard-bukkit:$worldGuardVersion")
    implementation("com.github.angeschossen:LandsAPI:6.28.11")
}

// We used to use the net.kyori.blossom (1.x) plugin to inject the list of
// translation files into Translator.java at compile time. That version of
// blossom is built on Gradle's deprecated Convention API and is broken on
// Gradle 9+; the 2.x rewrite uses a completely different (Pebble-template
// based) API that would be overkill for replacing a single token. Instead we
// do the token replacement ourselves with a plain Gradle Copy task.
//
// IMPORTANT — two earlier attempts at this got the source-set wiring wrong:
//   1. Copying the whole tree into a generated dir and ADDING it as an extra
//      srcDir while excluding Translator.java from the original: Gradle's
//      SourceDirectorySet applies one exclude pattern across ALL srcDirs, so
//      Translator.java vanished from both copies (missing symbol), while
//      every other file existed in both roots at once (duplicate class).
//   2. Copying only Translator.java into a generated dir and adding THAT as
//      an extra srcDir: same duplicate-class problem for every other class,
//      since src/main/java was still a source root too.
//
// The only bulletproof fix: copy the ENTIRE tree into build/, do the token
// replacement on the one file that needs it, and make the generated
// directory the ONLY java source root — src/main/java is never itself
// compiled. Exactly one copy of every class exists at compile time, period.
val translationFileNames = fileTree(sourceSets["main"].resources.srcDirs.first()).files
    .filter { it.path.contains("translations_[a-z].+?.yml".toRegex()) }
    .map { it.name }
    .toString()

val generatedSrcDir = layout.buildDirectory.dir("generated/sources/translator")

val generateTranslator = tasks.register<Copy>("generateTranslator") {
    from("src/main/java")
    into(generatedSrcDir)
    filesMatching("de/sean/blockprot/bukkit/Translator.java") {
        expand("TRANSLATION_FILES" to translationFileNames)
        filteringCharset = "UTF-8"
    }
    // expand() uses Groovy SimpleTemplateEngine ($TOKEN) syntax, matching the
    // "$TRANSLATION_FILES" placeholder already present in Translator.java.
}

sourceSets {
    main {
        java {
            // REPLACE the default source dirs entirely — do not add to them.
            // src/main/java is intentionally not a compiled source root; the
            // generated (post-token-replacement) copy is the only one.
            setSrcDirs(listOf(generatedSrcDir))
        }
    }
}

tasks.compileJava {
    dependsOn(generateTranslator)
}

// Every other task that reads the generated source tree (javadoc, sourcesJar,
// IDE sync tasks, etc.) needs the same explicit dependency — Gradle 9's task
// validation flags any consumer of generateTranslator's output that doesn't
// declare it, since implicit ordering can't be guaranteed under parallel
// execution or the configuration cache. sourcesJar was the first one to hit
// this; wiring it via withType() here means we don't have to remember to add
// dependsOn(generateTranslator) by hand every time a new task starts reading
// from src/main/java (which now really means "the generated copy").
tasks.withType<AbstractCopyTask> {
    if (name != generateTranslator.name) {
        dependsOn(generateTranslator)
    }
}
tasks.withType<Javadoc> {
    dependsOn(generateTranslator)
}

// IDEs (IntelliJ) index src/main/java directly regardless of the Gradle
// source set wiring above, so editing/navigation there still works as
// expected — only the actual javac invocation is redirected to the
// generated, token-replaced copy.

java {
    withJavadocJar()
    withSourcesJar()
}

tasks.processResources {
    inputs.property("version", project.version)

    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.javadoc {
    options {
        source = "25"
        encoding = "UTF-8"
        memberLevel = JavadocMemberLevel.PACKAGE
        (this as CoreJavadocOptions).addStringOption("Xdoclint:none", "-quiet")
    }

    this.isFailOnError = false
}

tasks.shadowJar {
    relocate("de.tr7zw.changeme.nbtapi", "de.sean.blockprot.bukkit.shaded.nbtapi")
    relocate("org.bstats", "de.sean.blockprot.bukkit.metrics")
    relocate("org.enginehub.squirrelid", "de.sean.blockprot.bukkit.squirrelid")
    // minimize()

    dependencies {
        this.include(project(":common"))
        this.include(dependency("org.jetbrains:annotations"))
        this.include(dependency("de.tr7zw:item-nbt-api"))
        this.include(dependency("org.bstats:bstats-base"))
        this.include(dependency("org.bstats:bstats-bukkit"))
        this.include(dependency("org.enginehub:squirrelid"))
    }

    archiveClassifier.set(
        if (ext["gitBranchName"] == "master" || ext["gitBranchName"] == "HEAD") "all"
        else "${ext["gitBranchName"]}-all")
    // archiveFileName.set("${base.archivesName.get()}-${archiveClassifier.get()}.jar")
}

tasks.build {
    dependsOn(tasks["javadocJar"])
    dependsOn(tasks.shadowJar)
}

tasks.runServer {
    downloadPlugins {
        url("https://download.luckperms.net/1561/bukkit/loader/LuckPerms-Bukkit-5.4.146.jar")
    }
    // NOTE: this downloads and runs vanilla PAPER, not Folia — there is no
    // officially maintained "run-folia" Gradle task as of writing. This is fine
    // for a quick compile/load smoke test, but it will NOT catch region-thread
    // violations (those only manifest under Folia's actual threading model).
    // For real Folia testing, manually download a server jar from
    // https://papermc.io/downloads/folia and run your shadowJar against it.
    minecraftVersion("1.21.5")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            groupId = project.group as String
            artifactId = project.name
            version = project.version as String

            from(components["java"])
        }
    }
    repositories {
        mavenLocal()
    }
}
