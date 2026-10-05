plugins {
    java
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:4.2.0")
    annotationProcessor("com.velocitypowered:velocity-api:4.2.0")
    // 运行时由 Velocity 自身提供同名类，仅编译期引用
    compileOnly("com.google.code.gson:gson:2.10.1")
    // 代理端自带的 YAML 解析库（Velocity 的 configurate-yaml 依赖引入），仅编译期引用
    compileOnly("org.yaml:snakeyaml:2.7")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}

tasks.named<Jar>("jar") {
    archiveFileName.set("MikuChat-Velocity-${project.version}.jar")
}
