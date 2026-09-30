plugins {
    base
    id("com.gradleup.shadow") version "9.6.1" apply false
}

allprojects {
    group = "io.github.origingate"
    version = providers.gradleProperty("version").get()
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://maven.elmakers.com/repository/") {
            content { includeModule("org.bukkit", "bukkit") }
            metadataSources { artifact() }
        }
    }
}

subprojects {
    apply(plugin = "java-library")
    dependencyLocking {
        lockAllConfigurations()
        // Gradle stores timestamped Maven builds under their base SNAPSHOT version in lock files.
        // These coordinates are fixed explicitly and checked by verification-metadata.xml.
        ignoredDependencies.addAll("com.velocitypowered:velocity-api", "com.velocitypowered:velocity-brigadier")
    }
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if ("${requested.group}:${requested.name}" == "com.velocitypowered:velocity-brigadier") {
                useVersion("1.0.0-20210613.082804-10")
            }
        }
    }
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
        options.encoding = "UTF-8"
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
    tasks.withType<Jar>().configureEach {
        if (name != "shadowJar") from(rootProject.file("LICENSE")) { into("META-INF") }
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.13.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
}
