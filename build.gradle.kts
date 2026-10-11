plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.6.1"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven {
        url = uri("https://repo.codemc.org/repository/maven-public/") // NBT
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    implementation("dev.dejvokep:boosted-yaml-spigot:1.5")      // Ref: https://github.com/dejvokep/boosted-yaml-spigot/releases
    implementation("dev.dejvokep:boosted-yaml:1.3.7")           // Ref: https://github.com/dejvokep/boosted-yaml/releases
    compileOnly("net.kyori:adventure-nbt:5.1.1")
    compileOnly(files("/home/martin/Repos/VexelCore/bukkit/build/libs/VexelCore-Bukkit-1.0.1-SNAPSHOT.jar"))

    // --- TEST DEPENDENCIES ---
    testImplementation("io.papermc.paper:paper-api:26.2.build.+")
    testImplementation("net.kyori:adventure-nbt:5.1.1")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.2:4.116.1")
    testImplementation(files("/home/martin/Repos/VexelCore/bukkit/build/libs/VexelCore-Bukkit-1.0.1-SNAPSHOT.jar"))

    // JUnit 6 — unified versioning, Java 17+ baseline, full Java 25 support
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Mockito 5.23.0 — includes ByteBuddy with Java 25 support and JDK 25 bug fixes
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.23.0")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    shadowJar {
        archiveClassifier.set("")
    }

    jar {
        enabled = false
    }

    processResources {
        val props = mapOf("version" to version, "description" to project.description)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }

    test {
        useJUnitPlatform()
    }
}