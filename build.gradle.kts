import de.undercouch.gradle.tasks.download.Download
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import java.io.RandomAccessFile
import java.util.*

buildscript {
    repositories { mavenCentral() }
    dependencies {
        classpath("org.apache.commons:commons-compress:1.22")
        classpath("org.tukaani:xz:1.9")

        classpath("net.sf.sevenzipjbinding:sevenzipjbinding:16.02-2.01")
        classpath("net.sf.sevenzipjbinding:sevenzipjbinding-all-platforms:16.02-2.01")
    }
}

plugins {
    id("java")
    id("maven-publish")
    id("signing")
    id("org.glavo.load-maven-publish-properties") version "0.1.0"
    id("io.github.gradle-nexus.publish-plugin") version "1.1.0"
    id("de.undercouch.download") version "5.3.1"
}

group = "org.glavo"
version = "0.3.0" + "-SNAPSHOT"
description = "Mesa Loader for windows"

val packageName = "org.glavo.mesa"

java {
    withSourcesJar()
}

tasks.compileJava {
    options.release.set(8)

    doLast {
        val tree = fileTree(destinationDirectory)
        tree.include("**/*.class")
        tree.forEach {
            RandomAccessFile(it, "rw").use { rf ->
                rf.seek(7)   // major version
                rf.write(50)   // java 6
                rf.close()
            }
        }
    }
}

tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "$packageName.Loader"
        )
    }
}

val `jar-x64` by tasks.creating(Jar::class) {
    dependsOn(tasks.jar)

    archiveClassifier.set("x64")

    manifest {
        attributes(
            "Premain-Class" to "$packageName.Loader"
        )
    }

    from(project.zipTree(tasks.jar.get().archiveFile.get())) {
        exclude("**/x86/*")
    }
}

val jars by tasks.creating {
    dependsOn(`jar-x64`)
}

val mesaVersion = "22.3.5"
val mesaCompiler = "mingw"

val mesaDir = buildDir.resolve("mesa-$mesaVersion-$mesaCompiler")
val mesaDistFile = buildDir.resolve("mesa3d-$mesaVersion-release-$mesaCompiler.7z")

val mesaArches = listOf("x64")
val mesaFiles = listOf("libglapi.dll", "libgallium_wgl.dll", "opengl32.dll", "dxil.dll")

val downloadMesa by tasks.creating(Download::class) {
    src("https://github.com/pal1000/mesa-dist-win/releases/download/$mesaVersion/${mesaDistFile.name}")
    dest(mesaDistFile)
    overwrite(false)
}

val extractMesaDlls by tasks.creating {
    dependsOn(downloadMesa)

    inputs.file(mesaDistFile)
    outputs.files(mesaArches.flatMap { arch ->  mesaFiles.map { fileName -> mesaDir.resolve("$arch/$fileName") } })

    doLast {
        SevenZip.initSevenZipFromPlatformJAR()
        SevenZip.openInArchive(null, RandomAccessFileInStream(RandomAccessFile(mesaDistFile, "r"))).use { archive ->
            val items = archive.simpleInterface.archiveItems.groupBy { it.path.replace('\\', '/') }.mapValues { it.value.first() }

            for (arch in mesaArches) {
                for (fileName in mesaFiles) {
                    val path = "$arch/$fileName"
                    val item = items.getOrElse(path) { throw Exception("$path not found") }

                    val targetFile = mesaDir.resolve(path)
                    targetFile.parentFile.mkdirs()

                    targetFile.outputStream().use { output ->
                        item.extractSlow {
                            output.write(it)
                            it.size
                        }
                    }
                }
            }
        }
    }
}

val versionFile = buildDir.resolve("version.properties")
val createVersionFile by tasks.creating {
    doLast {
        val p = Properties()
        p["loader.version"] = project.version.toString()
        p["mesa.version"] = "$mesaVersion-$mesaCompiler"
        versionFile.writer().use { p.store(it, null) }

    }
}

tasks.processResources {
    dependsOn(createVersionFile)

    into(packageName.replace('.', '/')) {
        from(versionFile)
        from(mesaDir)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.1")
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}

tasks.withType<GenerateModuleMetadata>().configureEach {
    enabled = false
}

configure<PublishingExtension> {
    publications {
        create<MavenPublication>("maven") {
            groupId = project.group.toString()
            version = project.version.toString()
            artifactId = project.name
            artifact(`jar-x64`)

            pom {
                name.set(project.name)
                description.set(project.description)
                url.set("https://github.com/Glavo/mesa-loader-windows")

                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("glavo")
                        name.set("Glavo")
                        email.set("zjx001202@gmail.com")
                    }
                }

                scm {
                    url.set("https://github.com/Glavo/mesa-loader-windows")
                }
            }
        }
    }
}

signing {
    useInMemoryPgpKeys(
        rootProject.ext["signing.keyId"].toString(),
        rootProject.ext["signing.key"].toString(),
        rootProject.ext["signing.password"].toString(),
    )
    sign(publishing.publications["maven"])
}

nexusPublishing {
    repositories {
        sonatype {
            stagingProfileId.set(rootProject.ext["sonatypeStagingProfileId"].toString())
            username.set(rootProject.ext["sonatypeUsername"].toString())
            password.set(rootProject.ext["sonatypePassword"].toString())
        }
    }
}
