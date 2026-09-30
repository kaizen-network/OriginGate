plugins { `java-library`; id("com.gradleup.shadow") }
dependencies { implementation("com.maxmind.db:maxmind-db:4.2.0") }
tasks.shadowJar {
    relocate("com.maxmind.db", "io.github.origingate.internal.maxmind17")
    exclude("module-info.class", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}
tasks.compileJava { options.release.set(17) }
