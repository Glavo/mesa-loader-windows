import java.io.RandomAccessFile

plugins {
    id("java")
    id("maven-publish")
    id("signing")
    id("org.glavo.load-maven-publish-properties") version "0.1.0"
    id("io.github.gradle-nexus.publish-plugin") version "1.1.0"
    id("de.undercouch.download") version "5.6.0"
}

group = "org.glavo"
version = "1.0.0" + "-SNAPSHOT"
description = "Mesa Loader for windows"

val packageName = "org.glavo.mesa"

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.javadoc {
    (options as CoreJavadocOptions).apply {
        addBooleanOption("Xdoclint:none", true)
    }
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

val mesaVersion = "24.3.2"
val mesaArches = listOf("x86", "x64", "arm64")
val mesaDrivers = listOf("llvmpipe", "d3d12", "zink")

val mesaFiles = listOf("libglapi.dll", "libgallium_wgl.dll", "opengl32.dll", "dxil.dll")
val mesaDir = layout.buildDirectory.dir("download").get().dir("mesa-$mesaVersion").asFile

val downloadMesa = tasks.register<de.undercouch.gradle.tasks.download.Download>("downloadMesa") {
    val urlBase = "https://github.com/mmozeiko/build-mesa/releases/download/$mesaVersion"

    for (arch in mesaArches) {
        for (driver in mesaDrivers) {
            src("$urlBase/mesa-$driver-$arch-$mesaVersion.zip")
        }
    }

    dest(mesaDir)
    overwrite(false)
}

val versionFile = mesaDir.resolve("version.properties")
val createVersionFile = tasks.register("createVersionFile") {
    doLast {
        versionFile.parentFile.mkdirs()
        versionFile.printWriter().use { writer ->
            writer.println("loader.version=${project.version}")
            writer.println("mesa.version=$mesaVersion")
        }
    }
}

fun Jar.addMesaDlls(arch: String) {
    for (driver in mesaDrivers) {
        into("$packageName.$arch.$driver".replace('.', '/')) {
            from(zipTree(mesaDir.resolve("mesa-$driver-$arch-$mesaVersion.zip")))
        }
    }
}

tasks.jar {
    for (arch in mesaArches) {
        addMesaDlls(arch)
    }
}

val jarTasks = listOf(tasks.jar) + mesaArches.map { arch ->
    tasks.register<Jar>("jar-$arch") {
        tasks.build.get().dependsOn(this)
        archiveClassifier.set(arch)

        from(sourceSets["main"].runtimeClasspath)

        addMesaDlls(arch)
    }
}

for (jarTask in jarTasks) {
    jarTask {
        dependsOn(downloadMesa, createVersionFile)
        manifest {
            attributes(
                "Premain-Class" to "$packageName.Loader"
            )
        }
        into(packageName.replace('.', '/')) {
            from(versionFile)
        }
    }
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

            for (arch in mesaArches) {
                artifact(tasks["jar-$arch"])
            }

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
