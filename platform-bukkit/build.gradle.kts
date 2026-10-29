plugins { `java-library`; id("com.gradleup.shadow") }
val probe: SourceSet = sourceSets.create("probe")
configurations[probe.implementationConfigurationName].extendsFrom(configurations.implementation.get())
dependencies {
    implementation(project(":presentation"))
    compileOnly("org.bukkit:bukkit:1.7.2-R0.3@jar")
    runtimeOnly("org.slf4j:slf4j-jdk14:2.0.17")
}
tasks.compileJava { options.release.set(8) }
tasks.named<JavaCompile>(probe.compileJavaTaskName) { options.release.set(8) }
tasks.jar { enabled = false }
tasks.shadowJar {
    archiveBaseName.set("OriginGate-Bukkit")
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
    mergeServiceFiles()
    relocate("org.yaml.snakeyaml", "io.github.origingate.internal.snakeyaml")
    relocate("org.mariadb.jdbc", "io.github.origingate.internal.mariadb")
    relocate("com.google.gson", "io.github.origingate.internal.gson")
    relocate("net.kyori", "io.github.origingate.internal.kyori")
    relocate("org.slf4j", "io.github.origingate.internal.slf4j")
    exclude("module-info.class", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}
tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
tasks.assemble { dependsOn(tasks.shadowJar) }
tasks.test {
    dependsOn(tasks.shadowJar)
    systemProperty("origingate.artifact", tasks.shadowJar.get().archiveFile.get().asFile.absolutePath)
}

tasks.register<JavaExec>("legacyRuntimeProbe") {
    description = "Check packaged MaxMind and SQLite on a real Java 8 runtime."
    group = "verification"
    dependsOn(tasks.shadowJar, tasks.named(probe.classesTaskName))
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(8)) })
    mainClass.set("io.github.origingate.bukkit.LegacyRuntimeProbe")
    classpath = files(providers.gradleProperty("legacyServerJar").orNull, probe.output, tasks.shadowJar.get().archiveFile)
    args(rootProject.file("core/src/test/resources/maxmind/GeoLite2-Country-Test.mmdb"), layout.buildDirectory.file("probe/legacy.db").get().asFile)
}
