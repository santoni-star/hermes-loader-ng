import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    id("com.android.application")
}

android {
    namespace = "com.hermes.loader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hermes.loader"
        minSdk = 24
        targetSdk = 34
        versionCode = 100
        versionName = "2.0.0-ng"
        resourceConfigurations += listOf("en", "uk")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // chroot-ng is a real executable shipped as a lib*.so file.
        // It must be extracted to nativeLibraryDir at install time: that is
        // the only place Android 10+ still allows an app to execve() from.
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libchrootng.so")
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    lint {
        // The loader talks to the network with plain HttpURLConnection and uses
        // framework-only UI on purpose; the default lint set is enough here.
        abortOnError = false
    }
}

dependencies {
    // RootFS archives are .tar.xz, and Android has no system tar/xz.
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")
}

// ---------------------------------------------------------------------------
// Published artefacts
// ---------------------------------------------------------------------------
// Two copies of every build are kept:
//
//   app-debug-web.apk  - the newest dashboard build, overwritten every time;
//   releases/*.apk     - one immutable file per version, named after the
//                        versionName/versionCode baked into it, so a build
//                        that turns out to be broken can be restored without
//                        trusting that the current sources still produce the
//                        same bytes.
//
// An archived file is never overwritten. If the same version is built again
// with different content (a real change without a version bump, or a
// non-reproducible rebuild) the copy is stored with a `-b2`/`-b3` suffix and
// Gradle warns loudly instead of silently destroying the previous artefact.

fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** Rewrites `releases/index.md` and `releases/SHA256SUMS.txt` from the files on disk. */
fun writeReleaseIndex(dir: File) {
    val apks = dir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }
        ?.sortedBy { it.name }
        ?: return
    val name = Regex("""^hermes-loader-(.+)-vc(\d+)-([a-z]+)(-b\d+)?\.apk$""")
    val rows = StringBuilder()
    rows.append("| APK | Version | versionCode | Variant | Size | SHA-256 | Built |\n")
    rows.append("|---|---|---|---|---|---|---|\n")
    val sums = StringBuilder("# SHA-256 of every archived APK (sha256sum -c compatible)\n")
    for (apk in apks) {
        val hash = sha256Of(apk)
        sums.append(hash).append("  ").append(apk.name).append('\n')
        val m = name.find(apk.name)
        val built = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
            .format(Date(apk.lastModified()))
        rows.append("| `").append(apk.name).append("` | ")
            .append(m?.groupValues?.get(1) ?: "?").append(" | ")
            .append(m?.groupValues?.get(2) ?: "?").append(" | ")
            .append(m?.groupValues?.get(3) ?: "?").append(" | ")
            .append(String.format(Locale.ROOT, "%.2f MiB", apk.length() / 1048576.0)).append(" | ")
            .append('`').append(hash).append("` | ")
            .append(built).append(" |\n")
    }
    File(dir, "SHA256SUMS.txt").writeText(sums.toString())
    File(dir, "index.md").writeText(
        """
        # APK archive

        Every APK built from this tree, in build order. Nothing here is ever
        overwritten: `app-debug-web.apk` in the project root is only a pointer
        to the newest build.

        Roll back with `adb install -r releases/<file>.apk`. Verify copies with
        `sha256sum -c releases/SHA256SUMS.txt`.

        Generated by `assembleDebug` / `assembleRelease`; do not edit by hand.
        See `README.md` in this directory for the policy.

        """.trimIndent() + "\n" + rows + "\n"
    )
}

fun archiveApk(
    project: Project,
    apk: File,
    variant: String,
    versionName: String,
    versionCode: Int,
    releasesDir: File,
    latest: File?,
) {
    if (latest != null) {
        apk.copyTo(latest, overwrite = true)
        project.logger.lifecycle("Bundled dashboard APK: ${latest.absolutePath}")
    }

    releasesDir.mkdirs()

    val base = "hermes-loader-$versionName-vc$versionCode-$variant"
    val digest = sha256Of(apk)
    var target = File(releasesDir, "$base.apk")
    if (target.isFile) {
        val identical = target.length() == apk.length() && sha256Of(target) == digest
        if (!identical) {
            var n = 2
            while (true) {
                val candidate = File(releasesDir, "$base-b$n.apk")
                if (!candidate.exists()) {
                    target = candidate
                    break
                }
                if (candidate.length() == apk.length() && sha256Of(candidate) == digest) {
                    target = candidate
                    break
                }
                n++
            }
            project.logger.warn(
                "WARNING: $versionName (versionCode $versionCode) produced different bytes " +
                    "than the archived copy."
            )
            project.logger.warn(
                "         Stored as ${target.name}; bump versionCode/versionName before shipping."
            )
        }
    }

    if (!target.isFile) {
        apk.copyTo(target, overwrite = false)
        project.logger.lifecycle("Archived: ${target.absolutePath}")
    } else {
        project.logger.lifecycle("Already archived: ${target.name}")
    }
    writeReleaseIndex(releasesDir)
}

fun newestApk(variant: String, project: Project): File? {
    val outDir = project.layout.buildDirectory.dir("outputs/apk/$variant").get().asFile
    return outDir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }
        ?.filterNot { it.name.contains("unsigned") }
        ?.maxByOrNull { it.lastModified() }
}

val apkVersionName: String = android.defaultConfig.versionName ?: "unknown"
val apkVersionCode: Int = android.defaultConfig.versionCode ?: 0
val releasesDir: File = rootProject.layout.projectDirectory.dir("releases").asFile

tasks.matching { it.name == "assembleDebug" }.configureEach {
    doLast {
        val apk = newestApk("debug", project)
        if (apk == null) {
            logger.lifecycle("No debug APK found to archive.")
        } else {
            archiveApk(
                project = project,
                apk = apk,
                variant = "debug",
                versionName = apkVersionName,
                versionCode = apkVersionCode,
                releasesDir = releasesDir,
                latest = rootProject.layout.projectDirectory.file("app-debug-web.apk").asFile,
            )
        }
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    doLast {
        val apk = newestApk("release", project)
        if (apk == null) {
            logger.lifecycle("No signed release APK found to archive.")
        } else {
            archiveApk(
                project = project,
                apk = apk,
                variant = "release",
                versionName = apkVersionName,
                versionCode = apkVersionCode,
                releasesDir = releasesDir,
                latest = null,
            )
        }
    }
}
