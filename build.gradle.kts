plugins {
    java
}

group = "org.bluemaps"
version = "1.0.0"

repositories {
    mavenCentral()

    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }

    maven {
        name = "bluecolored"
        url = uri("https://repo.bluecolored.de/releases")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    compileOnly("de.bluecolored:bluemap-api:2.7.3")

    // Put the PlayerWarps plugin jar inside the /libs folder
    compileOnly(files("libs/PlayerWarps-5.0.0.jar"))}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }

    processResources {
        filteringCharset = "UTF-8"
    }

    jar {
        archiveBaseName.set("PlayerWarpsBlueMap")
        archiveVersion.set(project.version.toString())
    }
}