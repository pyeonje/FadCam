pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        flatDir {
            dirs("app/libs")
        }
    }
}

rootProject.name = "FadCam"
include(":app")

// Include patched Media3 as composite build for live streaming support
// Clone it once: git clone --depth 1 https://github.com/anonfaded/media3-patched.git /tmp/media3-patched
// Or set your own path in local.properties: media3.patched.path=/your/path
//
// ⚠️ media3 is PINNED to 1.8.0 — do NOT bump it to 1.9+/1.10+/1.11+ without
// first updating the media3-patched repo. The composite build below substitutes
// media3-muxer/common/container with the patched forks (lib-muxer/lib-common/
// lib-container), which are built against 1.8.0. Bumping media3 while the
// patch is on 1.8.0 breaks the muxer's hybrid finalization fixes (stsz/trun
// handling). To upgrade: update media3-patched to the new version first, verify
// the muxer patch still applies, then bump `media3` in gradle/libs.versions.toml.
val media3PatchedPath = if (file("local.properties").exists()) {
    val props = java.util.Properties()
    file("local.properties").inputStream().use { props.load(it) }
    props.getProperty("media3.patched.path", "/tmp/media3-patched")
} else {
    "/tmp/media3-patched"
}

val media3Dir = file(media3PatchedPath)
check(media3Dir.isDirectory) { "고정 Media3 작업 사본이 없습니다. local.properties의 media3.patched.path를 확인하세요." }
val buildLock = groovy.json.JsonSlurper().parse(file("personal-build.lock.json")) as Map<*, *>
val expectedMedia3Commit = buildLock["media3Commit"] as? String
check(expectedMedia3Commit?.matches(Regex("[0-9a-f]{40}")) == true) { "Media3 커밋 잠금값이 올바르지 않습니다." }
fun media3Git(vararg args: String): String = try {
    providers.exec {
        workingDir(media3Dir)
        commandLine(listOf("git", "--no-optional-locks") + args)
    }.standardOutput.asText.get().trim()
} catch (e: Exception) {
    throw GradleException("고정 Media3 Git 작업 사본을 확인하지 못했습니다.", e)
}
check(media3Git("rev-parse", "--show-prefix").isEmpty() &&
        media3Git("rev-parse", "HEAD") == expectedMedia3Commit) {
    "Media3 커밋이 personal-build.lock.json의 잠금값과 다릅니다."
}
check(media3Git("status", "--porcelain=v1", "--untracked-files=all").isEmpty()) {
    "고정 Media3 작업 사본에 커밋되지 않은 변경이 있습니다. 변경을 보존한 채 깨끗한 사본을 지정하세요."
}
run {
    includeBuild(media3PatchedPath) {
        dependencySubstitution {
            substitute(module("androidx.media3:media3-muxer")).using(project(":lib-muxer"))
            substitute(module("androidx.media3:media3-common")).using(project(":lib-common"))
            substitute(module("androidx.media3:media3-container")).using(project(":lib-container"))
        }
    }
}
