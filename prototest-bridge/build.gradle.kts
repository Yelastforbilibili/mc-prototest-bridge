plugins {
    `java-library`
    // Fat jar: bundles the plugin's runtime dependencies into one deployable jar.
    id("com.gradleup.shadow") version "8.3.6"
}

group = "com.prototest.bridge"
version = "0.1.0"

// The plugin reads vanilla internals (Block.BLOCK_STATE_REGISTRY, BuiltInRegistries, CraftBlockData) that
// the paper-api artifact does not expose, so compiling needs the server's own jars. Point this at a
// directory that has already run once (it contains versions/ and libraries/ after the first start):
//   ./gradlew build -PserverDir=C:\path\to\paper-server
val serverDir = (findProperty("serverDir") as String?)?.let { file(it) }

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // The Paper server provides this API at runtime, so it must not be bundled.
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
    if (serverDir != null) {
        compileOnly(files(fileTree("${serverDir}/libraries") { include("**/*.jar") }.files))
        compileOnly(files(fileTree("${serverDir}/versions") { include("**/*.jar") }.files))
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks {
    compileJava {
        if (serverDir == null) {
            doFirst {
                throw GradleException(
                    "Pass -PserverDir=<paper server directory>: the plugin compiles against the server's " +
                        "own jars because paper-api does not expose the vanilla block state registry."
                )
            }
        }
    }
    shadowJar {
        archiveBaseName.set("prototest-bridge")
        archiveClassifier.set("")
    }
    assemble {
        dependsOn(shadowJar)
    }
    processResources {
        // Keep plugin.yml in sync with the build version.
        filesMatching("plugin.yml") {
            expand("version" to version)
        }
    }
}
