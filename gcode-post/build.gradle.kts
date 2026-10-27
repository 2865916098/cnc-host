plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":core-model"))
    testImplementation(project(":dxf-parser"))
    testImplementation(project(":cam-engine"))
    testImplementation("junit:junit:4.13.2")
}
