plugins {
    alias(libs.plugins.fabric.loom)
}

val archivesBaseName = providers.gradleProperty("archives_base_name").get()
val mavenGroup = providers.gradleProperty("maven_group").get()

base {
    archivesName = archivesBaseName
    version = libs.versions.mod.version.get()
    group = mavenGroup
}

repositories {
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }
}

dependencies {
    // Fabric
    minecraft(libs.minecraft)
    mappings(variantOf(libs.yarn) { classifier("v2") })
    modImplementation(libs.fabric.loader)

    // Meteor
    modImplementation(libs.meteor.client)

    // Baritone - compile only, so the addon still loads when Baritone is not installed.
    modCompileOnly(libs.baritone)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// The planner is pure logic over positions, so it can be exercised headlessly. These tests run against
// the real remapped Minecraft classes rather than stubs, which is why the main classpath is reused.
sourceSets {
    test {
        compileClasspath += sourceSets.main.get().compileClasspath
        runtimeClasspath += sourceSets.main.get().runtimeClasspath
    }
}

tasks.register<JavaExec>("plannerTest") {
    group = "verification"
    description = "Runs the FlightPlanner logic tests headlessly."
    dependsOn("testClasses")
    classpath = sourceSets.test.get().runtimeClasspath + sourceSets.test.get().output
    mainClass.set("dev.basehunt.bhclient.PlannerTest")
}

// Guards against Meteor renaming the ElytraFly settings/enums this addon drives, which would compile
// fine but fail at runtime.
tasks.register<JavaExec>("apiContractTest") {
    group = "verification"
    description = "Checks the Meteor ElytraFly API surface this addon depends on."
    dependsOn("testClasses")

    val compileCp = configurations.getByName("compileClasspath").files

    val meteorJar = compileCp.firstOrNull { it.name.contains("meteor-client") }
        ?: throw GradleException("meteor-client jar not found on the compile classpath")

    val minecraftJar = compileCp.firstOrNull { it.name.startsWith("minecraft-merged") }
        ?: throw GradleException("minecraft jar not found on the compile classpath")

    classpath = sourceSets.test.get().output
    mainClass.set("dev.basehunt.bhclient.ApiContractTest")
    args(meteorJar.absolutePath, minecraftJar.absolutePath)
}

tasks.register("verifyAddon") {
    group = "verification"
    description = "Runs the headless planner and API contract tests."
    dependsOn("plannerTest", "apiContractTest")
}

// The planner tests are a plain main class rather than JUnit, so the default test task has nothing to
// discover and would otherwise fail the build.
tasks.named<Test>("test") {
    failOnNoDiscoveredTests = false
}

tasks.named("check") {
    dependsOn("verifyAddon")
}

tasks {
    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "minecraft_version" to libs.versions.minecraft.get(),
            "loader_version" to libs.versions.fabric.loader.get(),
            "jdk_version" to libs.versions.jdk.get(),
        )

        inputs.properties(propertyMap)
        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    jar {
        inputs.property("archivesName", archivesBaseName)

        from("LICENSE") {
            rename { "${it}_$archivesBaseName" }
        }
    }

    withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(
            listOf(
                "-Xlint:deprecation",
                "-Xlint:unchecked"
            )
        )
    }
}
